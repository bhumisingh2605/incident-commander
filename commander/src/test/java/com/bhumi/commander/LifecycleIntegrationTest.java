
package com.bhumi.commander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhumi.commander.agent.RootCause;
import com.bhumi.commander.agent.RootCauseGuard;
import com.bhumi.commander.remediation.ActionExecutor;
import com.bhumi.commander.remediation.ProposalService;
import com.bhumi.commander.tools.ServiceRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {
        "COMMANDER_VIEWER_PASSWORD=test-viewer-password-1",
        "COMMANDER_OPERATOR_PASSWORD=test-operator-password-1",
        "COMMANDER_WEBHOOK_PASSWORD=test-webhook-password-1"
})
@Import(TestPostgres.class)
class LifecycleIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ActionExecutor executor;

    @Autowired
    ProposalService proposals;

    @BeforeEach
    void cleanDatabase() {
        for (String table : List.of(
                "action_proposals",
                "alerts",
                "agent_runs",
                "tool_calls",
                "deployments",
                "incidents")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private long incident(String status, boolean closed) {
        return jdbc.sql(
                        "INSERT INTO incidents " +
                                "(title, status, service, resolved_at) " +
                                "VALUES ('test', :s, 'payment-service', " +
                                (closed ? "now()" : "NULL") + ") RETURNING id")
                .param("s", status)
                .query(Long.class)
                .single();
    }

    private long proposal(
            long incidentId,
            String type,
            String service,
            String status) {

        long id = jdbc.sql("""
                INSERT INTO action_proposals
                    (incident_id, action_type, target_service,
                     rationale, status, decided_at)
                VALUES (:i, :t, :s, 'test', :st, now())
                RETURNING id
                """)
                .param("i", incidentId)
                .param("t", type)
                .param("s", service)
                .param("st", status)
                .query(Long.class)
                .single();

        if (status.equals("EXECUTING")) {
            jdbc.sql("""
                UPDATE action_proposals
                SET claimed_at = now()
                WHERE id = :id
                """)
                    .param("id", id)
                    .update();
        }

        if (status.equals("EXECUTED")) {
            jdbc.sql("""
                UPDATE action_proposals
                SET executed_at = now()
                WHERE id = :id
                """)
                    .param("id", id)
                    .update();
        }

        return id;
    }

    private String proposalStatus(long id) {
        return jdbc.sql(
                        "SELECT status FROM action_proposals WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private String incidentStatus(long id) {
        return jdbc.sql(
                        "SELECT status FROM incidents WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private boolean incidentOpen(long id) {
        return jdbc.sql(
                        "SELECT resolved_at IS NULL FROM incidents WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    // ---- Migrations and audit trail ---------------------------------------

    @Test
    void migrationsBuildTheWholeSchemaOnACleanDatabase() {
        List<String> tables = jdbc.sql("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """)
                .query(String.class)
                .list();

        assertThat(tables).contains(
                "incidents",
                "alerts",
                "agent_runs",
                "tool_calls",
                "deployments",
                "action_proposals",
                "audit_events",
                "flyway_schema_history"
        );
    }

    @Test
    void anApprovalIsRecordedWithTheApprover() {
        long inc = incident("AWAITING_APPROVAL", false);
        long p = proposal(
                inc, "RESTART_SERVICE", "order-service", "PENDING");

        jdbc.sql("""
                UPDATE action_proposals
                SET status = 'APPROVED',
                    decided_by = 'alice',
                    decided_at = now()
                WHERE id = :id
                """)
                .param("id", p)
                .update();

        String actor = jdbc.sql("""
                SELECT actor
                FROM audit_events
                WHERE subject_type = 'PROPOSAL'
                  AND subject_id = :id
                  AND to_status = 'APPROVED'
                """)
                .param("id", p)
                .query(String.class)
                .single();

        assertThat(actor).isEqualTo("alice");
    }

    @Test
    void auditEventsCanNeitherBeChangedNorDeleted() {
        proposal(
                incident("DIAGNOSED", false),
                "RESTART_SERVICE",
                "order-service",
                "PENDING"
        );

        assertThatThrownBy(() ->
                jdbc.sql("UPDATE audit_events SET actor = 'mallory'").update()
        ).hasMessageContaining("append-only");

        assertThatThrownBy(() ->
                jdbc.sql("DELETE FROM audit_events").update()
        ).hasMessageContaining("append-only");
    }

    @Test
    void anIncidentCannotBeClosedWhileAnActionIsExecuting() {
        long inc = incident("REMEDIATING", false);
        long p = proposal(
                inc, "RESTART_SERVICE", "payment-service", "EXECUTING");

        jdbc.sql("""
                UPDATE incidents
                SET status = 'RESOLVED', resolved_at = now()
                WHERE id = :id
                """)
                .param("id", inc)
                .update();

        assertThat(incidentStatus(inc)).isEqualTo("REMEDIATING");
        assertThat(incidentOpen(inc)).isTrue();

        jdbc.sql("""
                UPDATE action_proposals
                SET status = 'EXECUTED', executed_at = now()
                WHERE id = :id
                """)
                .param("id", p)
                .update();

        jdbc.sql("""
                UPDATE incidents
                SET status = 'RESOLVED', resolved_at = now()
                WHERE id = :id
                """)
                .param("id", inc)
                .update();

        assertThat(incidentStatus(inc)).isEqualTo("RESOLVED");
        assertThat(incidentOpen(inc)).isFalse();
    }

    // ---- Executor refusals -----------------------------------------------
    // These tests should not execute Docker actions.

    @Test
    void anUnknownProposalIsNotFound() {
        assertThat(executor.execute(999_999).outcome())
                .isEqualTo(ActionExecutor.Outcome.NOT_FOUND);
    }

    @Test
    void aPendingProposalDoesNotRun() {
        long p = proposal(
                incident("AWAITING_APPROVAL", false),
                "RESTART_SERVICE",
                "order-service",
                "PENDING"
        );

        assertThat(executor.execute(p).outcome())
                .isEqualTo(ActionExecutor.Outcome.NOT_APPROVED);

        assertThat(proposalStatus(p)).isEqualTo("PENDING");
    }

    @Test
    void anApprovedActionOnAClosedIncidentIsRefusedAndExpired() {
        long p = proposal(
                incident("RESOLVED", true),
                "RESTART_SERVICE",
                "order-service",
                "APPROVED"
        );

        assertThat(executor.execute(p).outcome())
                .isEqualTo(ActionExecutor.Outcome.INCIDENT_CLOSED);

        assertThat(proposalStatus(p)).isEqualTo("EXPIRED");
    }

    @Test
    void anActionOutsideTheAllowListIsBlocked() {
        long p = proposal(
                incident("REMEDIATING", false),
                "WIPE_DATA",
                "payment-service",
                "APPROVED"
        );

        ActionExecutor.Result result = executor.execute(p);

        assertThat(result.outcome())
                .isEqualTo(ActionExecutor.Outcome.FAILED);
        assertThat(result.message()).contains("allow-list");
        assertThat(proposalStatus(p)).isEqualTo("FAILED");
    }

    @Test
    void aServiceOutsideTheAllowListIsBlocked() {
        long p = proposal(
                incident("REMEDIATING", false),
                "RESTART_SERVICE",
                "commander-db",
                "APPROVED"
        );

        assertThat(executor.execute(p).outcome())
                .isEqualTo(ActionExecutor.Outcome.FAILED);

        assertThat(proposalStatus(p)).isEqualTo("FAILED");
    }

    @Test
    void aSecondActionOnTheSameServiceIsRefusedWhileOneIsRunning() {
        proposal(
                incident("REMEDIATING", false),
                "RESTART_SERVICE",
                "payment-service",
                "EXECUTING"
        );

        long p = proposal(
                incident("AWAITING_APPROVAL", false),
                "RESTART_SERVICE",
                "payment-service",
                "APPROVED"
        );

        assertThat(executor.execute(p).outcome())
                .isEqualTo(ActionExecutor.Outcome.BUSY);

        assertThat(proposalStatus(p)).isEqualTo("APPROVED");
    }

    @Test
    void aRecentActionOnTheSameServiceTriggersTheCooldown() {
        proposal(
                incident("RESOLVED", true),
                "RESTART_SERVICE",
                "payment-service",
                "EXECUTED"
        );

        long p = proposal(
                incident("AWAITING_APPROVAL", false),
                "RESTART_SERVICE",
                "payment-service",
                "APPROVED"
        );

        assertThat(executor.execute(p).outcome())
                .isEqualTo(ActionExecutor.Outcome.COOLDOWN);

        assertThat(proposalStatus(p)).isEqualTo("APPROVED");
    }

    // ---- Hostile model output --------------------------------------------

    @Test
    void onlyAllowListedActionsOnKnownServicesBecomeProposals() {
        long inc = incident("DIAGNOSED", false);

        RootCause hostile = new RootCause(
                "summary",
                "order-service",
                "APPLICATION_ERROR",
                List.of("e"),
                0.9,
                List.of(
                        "WIPE_DATA payment-service",
                        "RESTART_SERVICE payment-service; docker rm -f commander-db",
                        "RESTART_SERVICE evil-service",
                        "INVESTIGATE_MANUALLY order-service",
                        "ROLLBACK_DEPLOY order-service"
                )
        );

        proposals.createFrom(inc, hostile);

        List<String> created = jdbc.sql("""
                SELECT action_type || ' ' || target_service
                FROM action_proposals
                WHERE incident_id = :i
                """)
                .param("i", inc)
                .query(String.class)
                .list();

        assertThat(created)
                .containsExactly("ROLLBACK_DEPLOY order-service");
    }

    // ---- Label guardrail --------------------------------------------------

    private RootCauseGuard guard() {
        return new RootCauseGuard(jdbc, new ServiceRegistry());
    }

    private static RootCause cause(String service, String category) {
        return new RootCause(
                "summary",
                service,
                category,
                List.of("fact"),
                0.8,
                List.of()
        );
    }

    private RootCause applyGuard(
            String service, String category, String alertText) {

        return guard().apply(
                cause(service, category),
                alertText,
                "Test evidence for " + service
        );
    }

    @Test
    void aRecentDeployToTheSuspectedServiceMakesItABadDeploy() {
        jdbc.sql("""
            INSERT INTO deployments (service, version, deployed_by)
            VALUES ('payment-service', 'v9', 'test')
            """).update();

        RootCause out = applyGuard(
                "payment-service",
                "APPLICATION_ERROR",
                "HighErrorRate firing on order-service"
        );

        assertThat(out.category()).isEqualTo("BAD_DEPLOY");
        assertThat(out.evidence()).anyMatch(e -> e.contains("Guardrail"));
    }

    @Test
    void aDeployToADifferentServiceChangesNothing() {
        jdbc.sql("""
            INSERT INTO deployments (service, version, deployed_by)
            VALUES ('order-service', 'v9', 'test')
            """).update();

        RootCause out = applyGuard(
                "payment-service",
                "APPLICATION_ERROR",
                "HighErrorRate firing on order-service"
        );

        assertThat(out.category()).isEqualTo("APPLICATION_ERROR");
    }

    @Test
    void aServiceThatCallsNothingCannotHaveADependencyFailure() {
        RootCause out = applyGuard(
                "payment-service",
                "DEPENDENCY_FAILURE",
                "HighErrorRate firing on order-service"
        );

        assertThat(out.category()).isEqualTo("APPLICATION_ERROR");
    }

    @Test
    void forALatencyAlertTheLabelBecomesSlowDependency() {
        RootCause out = applyGuard(
                "payment-service",
                "DEPENDENCY_FAILURE",
                "HighLatencyP95 firing on order-service: p95 latency above 1s"
        );

        assertThat(out.category()).isEqualTo("SLOW_DEPENDENCY");
    }
    @Test
    void anEscalatedIncidentKeepsItsVerdictWhenItsAlertsClear() {
        long inc = incident("ESCALATED", false);
        jdbc.sql("UPDATE incidents SET status = 'RESOLVED', resolved_at = now() WHERE id = :id").param("id", inc).update();
        assertThat(incidentStatus(inc)).isEqualTo("ESCALATED");
        assertThat(incidentOpen(inc)).isFalse();
    }

    @Test
    void anIncidentThatWasNotEscalatedStillResolvesNormally() {
        long inc = incident("DIAGNOSED", false);
        jdbc.sql("UPDATE incidents SET status = 'RESOLVED', resolved_at = now() WHERE id = :id").param("id", inc).update();
        assertThat(incidentStatus(inc)).isEqualTo("RESOLVED");
    }
}
