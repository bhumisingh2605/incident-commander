
package com.bhumi.commander.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import javax.sql.DataSource;

import com.bhumi.commander.agent.RootCause;
import com.bhumi.commander.tools.ServiceRegistry;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProposalServicePostgresIntegrationTest {

    private static JdbcClient jdbc;
    private static ProposalService service;

    @BeforeAll
    static void initializeDatabase() {
        DataSource dataSource = SharedPostgres.dataSource();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbc = JdbcClient.create(dataSource);

        ServiceRegistry registry = mock(ServiceRegistry.class);

        when(registry.known("order-service")).thenReturn(true);
        when(registry.known("payment-service")).thenReturn(true);
        when(registry.known("unknown-service")).thenReturn(false);

        service = new ProposalService(jdbc, registry);
    }

    @BeforeEach
    void cleanTestData() {
        jdbc.sql("""
                DELETE FROM action_proposals
                WHERE incident_id IN (
                    SELECT id
                    FROM incidents
                    WHERE title LIKE 'integration-test-%'
                )
                """).update();

        jdbc.sql("""
                DELETE FROM incidents
                WHERE title LIKE 'integration-test-%'
                """).update();
    }

    private long createIncident(boolean resolved) {
        String sql = resolved
                ? """
                  INSERT INTO incidents (title, status, resolved_at)
                  VALUES (:title, 'OPEN', now())
                  RETURNING id
                  """
                : """
                  INSERT INTO incidents (title, status, resolved_at)
                  VALUES (:title, 'OPEN', NULL)
                  RETURNING id
                  """;

        return jdbc.sql(sql)
                .param("title", "integration-test-incident")
                .query(Long.class)
                .single();
    }

    private long createPendingProposal(long incidentId) {
        return jdbc.sql("""
                INSERT INTO action_proposals
                    (incident_id, action_type, target_service,
                     rationale, status)
                VALUES
                    (:incidentId, 'RESTART_SERVICE', 'order-service',
                     'integration-test', 'PENDING')
                RETURNING id
                """)
                .param("incidentId", incidentId)
                .query(Long.class)
                .single();
    }

    private RootCause rootCause(
            String serviceName,
            List<String> actions) {

        return new RootCause(
                "integration test",
                serviceName,
                "APPLICATION_ERROR",
                List.of("integration-test evidence"),
                0.9,
                actions);
    }

    // --------------------------------------------------
    // Tests for decide()
    // --------------------------------------------------

    @Test
    void shouldApprovePendingProposalForUnresolvedIncident() {
        long incidentId = createIncident(false);
        long proposalId = createPendingProposal(incidentId);

        ProposalService.Outcome outcome =
                service.decide(
                        proposalId, true, "integration-test-user");

        assertEquals(ProposalService.Outcome.OK, outcome);

        String status = jdbc.sql("""
                SELECT status
                FROM action_proposals
                WHERE id = :id
                """)
                .param("id", proposalId)
                .query(String.class)
                .single();

        assertEquals("APPROVED", status);
    }

    @Test
    void shouldCancelPendingProposalForResolvedIncident() {
        long incidentId = createIncident(true);
        long proposalId = createPendingProposal(incidentId);

        ProposalService.Outcome outcome =
                service.decide(
                        proposalId, true, "integration-test-user");

        assertEquals(
                ProposalService.Outcome.INCIDENT_RESOLVED, outcome);

        String status = jdbc.sql("""
                SELECT status
                FROM action_proposals
                WHERE id = :id
                """)
                .param("id", proposalId)
                .query(String.class)
                .single();

        assertEquals("CANCELLED", status);
    }

    @Test
    void shouldReturnNotFoundForMissingProposal() {
        ProposalService.Outcome outcome =
                service.decide(
                        -999L, true, "integration-test-user");

        assertEquals(ProposalService.Outcome.NOT_FOUND, outcome);
    }

    // --------------------------------------------------
    // Tests for createFrom()
    // --------------------------------------------------

    @Test
    void shouldCreateProposalForKnownServiceAndAllowedAction() {
        long incidentId = createIncident(false);

        int created = service.createFrom(
                incidentId,
                rootCause(
                        "order-service",
                        List.of("RESTART_SERVICE")));

        assertEquals(1, created);

        String status = jdbc.sql("""
                SELECT status
                FROM action_proposals
                WHERE incident_id = :id
                """)
                .param("id", incidentId)
                .query(String.class)
                .single();

        assertEquals("PENDING", status);
    }

    @Test
    void shouldNotCreateProposalForResolvedIncident() {
        long incidentId = createIncident(true);

        int created = service.createFrom(
                incidentId,
                rootCause(
                        "order-service",
                        List.of("RESTART_SERVICE")));

        assertEquals(0, created);
    }

    @Test
    void shouldIgnoreUnsupportedAction() {
        long incidentId = createIncident(false);

        int created = service.createFrom(
                incidentId,
                rootCause(
                        "order-service",
                        List.of("DELETE_DATABASE")));

        assertEquals(0, created);
    }

    @Test
    void shouldIgnoreUnknownService() {
        long incidentId = createIncident(false);

        int created = service.createFrom(
                incidentId,
                rootCause(
                        "unknown-service",
                        List.of("RESTART_SERVICE")));

        assertEquals(0, created);
    }

    @Test
    void shouldNotCreateDuplicateActiveProposal() {
        long incidentId = createIncident(false);

        int first = service.createFrom(
                incidentId,
                rootCause(
                        "order-service",
                        List.of("RESTART_SERVICE")));

        int second = service.createFrom(
                incidentId,
                rootCause(
                        "order-service",
                        List.of("RESTART_SERVICE")));

        assertEquals(1, first);
        assertEquals(0, second);
    }

    @Test
    void shouldRespectTwoActiveProposalLimit() {
        long incidentId = createIncident(false);

        int created = service.createFrom(
                incidentId,
                rootCause(
                        "order-service",
                        List.of(
                                "RESTART_SERVICE",
                                "ROLLBACK_DEPLOY")));

        assertEquals(2, created);

        int additional = service.createFrom(
                incidentId,
                rootCause(
                        "payment-service",
                        List.of("RESTART_SERVICE")));

        assertEquals(0, additional);
    }
}
