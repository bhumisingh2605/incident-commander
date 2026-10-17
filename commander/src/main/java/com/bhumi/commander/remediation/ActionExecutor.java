package com.bhumi.commander.remediation;

import com.bhumi.commander.tools.ServiceRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ActionExecutor {

    public enum Outcome { EXECUTED, FAILED, NOT_FOUND, NOT_APPROVED, COOLDOWN, INCIDENT_CLOSED, BUSY }

    public record Result(Outcome outcome, String message) {}

    private record Claim(Result refusal, long incidentId, String type, String service) {}

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);
    private static final Set<String> ALLOWED = Set.of("RESTART_SERVICE", "ROLLBACK_DEPLOY");
    private static final int COOLDOWN_MINUTES = 5;

    private final JdbcClient jdbc;
    private final ServiceRegistry services;
    private final Verifier verifier;
    private final TransactionTemplate tx;

    public ActionExecutor(JdbcClient jdbc, ServiceRegistry services, Verifier verifier,
                          PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.services = services;
        this.verifier = verifier;
        this.tx = new TransactionTemplate(txManager);
    }

    public Result execute(long proposalId) {
        Claim claim;
        try {
            claim = claim(proposalId);
        } catch (DataAccessException e) {
            log.error("Could not claim proposal {}: {}", proposalId, e.toString());
            return new Result(Outcome.FAILED, "database unavailable; nothing was executed");
        }
        if (claim.refusal() != null) return claim.refusal();

        long incidentId = claim.incidentId();
        String service = claim.service();
        try {
            String message = claim.type().equals("RESTART_SERVICE") ? restart(service) : rollback(service);
            if (!finish(proposalId, "EXECUTED", message)) {
                log.warn("Proposal {} finished after it was marked interrupted; not verifying", proposalId);
                return new Result(Outcome.FAILED,
                        "the action finished after the proposal was marked interrupted; check the service");
            }
            verifier.scheduleVerification(incidentId, service);
            log.info("Proposal {} (incident {}) executed: {}", proposalId, incidentId, message);
            return new Result(Outcome.EXECUTED, message);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            finish(proposalId, "FAILED", message);
            escalate(incidentId);
            log.error("Proposal {} (incident {}) failed: {}", proposalId, incidentId, message);
            return new Result(Outcome.FAILED, message);
        }
    }

    /** Everything that decides whether an action may run happens in one transaction, under locks. */
    private Claim claim(long proposalId) {
        return tx.execute(ts -> {
            List<Map<String, Object>> rows = jdbc.sql(
                            "SELECT incident_id, action_type, target_service FROM action_proposals WHERE id = :id")
                    .param("id", proposalId).query().listOfRows();
            if (rows.isEmpty()) return refused(Outcome.NOT_FOUND, "no such proposal");

            long incidentId = ((Number) rows.get(0).get("incident_id")).longValue();
            String type = (String) rows.get(0).get("action_type");
            String service = (String) rows.get(0).get("target_service");

            // Lock order: service first, then the incident row. Other code only ever takes the incident row.
            jdbc.sql("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtext(:s))) x")
                    .param("s", service).query(Long.class).single();
            List<Map<String, Object>> incident = jdbc
                    .sql("SELECT resolved_at FROM incidents WHERE id = :i FOR UPDATE")
                    .param("i", incidentId).query().listOfRows();
            String current = jdbc.sql("SELECT status FROM action_proposals WHERE id = :id")
                    .param("id", proposalId).query(String.class).single();

            if (incident.isEmpty() || incident.get(0).get("resolved_at") != null) {
                jdbc.sql("""
                        UPDATE action_proposals SET status = 'EXPIRED', executed_at = now(),
                          result = 'The incident was closed before this action ran.'
                        WHERE id = :id AND status IN ('PENDING', 'APPROVED')
                        """).param("id", proposalId).update();
                return refused(Outcome.INCIDENT_CLOSED, "the incident is closed; actions on closed incidents do not run");
            }
            if (!"APPROVED".equals(current)) {
                return refused(Outcome.NOT_APPROVED, "proposal is " + current + "; only APPROVED proposals can run");
            }
            if (!ALLOWED.contains(type) || !services.known(service)) {
                jdbc.sql("UPDATE action_proposals SET status = 'FAILED', executed_at = now(), result = :r WHERE id = :id")
                        .param("r", "blocked: action or service is not on the allow-list").param("id", proposalId).update();
                log.warn("Proposal {} blocked: {} on {} is not allowed", proposalId, type, service);
                return refused(Outcome.FAILED, "action or service is not on the allow-list");
            }

            Long running = jdbc.sql("SELECT count(*) FROM action_proposals WHERE target_service = :s AND status = 'EXECUTING'")
                    .param("s", service).query(Long.class).single();
            if (running != null && running > 0) {
                return refused(Outcome.BUSY, "another action is already running on " + service);
            }
            Long recent = jdbc.sql("""
                    SELECT count(*) FROM action_proposals
                    WHERE target_service = :s AND status = 'EXECUTED' AND executed_at > :since
                    """)
                    .param("s", service)
                    .param("since", OffsetDateTime.now().minusMinutes(COOLDOWN_MINUTES))
                    .query(Long.class).single();
            if (recent != null && recent > 0) {
                return refused(Outcome.COOLDOWN, service + " was acted on in the last "
                        + COOLDOWN_MINUTES + " minutes; try again later");
            }

            jdbc.sql("UPDATE action_proposals SET status = 'EXECUTING', claimed_at = now() WHERE id = :id AND status = 'APPROVED'")
                    .param("id", proposalId).update();
            jdbc.sql("UPDATE incidents SET status = 'REMEDIATING', updated_at = now() WHERE id = :i AND resolved_at IS NULL")
                    .param("i", incidentId).update();
            return new Claim(null, incidentId, type, service);
        });
    }

    private static Claim refused(Outcome outcome, String message) {
        return new Claim(new Result(outcome, message), 0, null, null);
    }

    /** Records the result only while the proposal is still EXECUTING (recovery may have interrupted it). */
    private boolean finish(long id, String status, String result) {
        String text = result == null ? null : (result.length() > 1000 ? result.substring(0, 1000) : result);
        return jdbc.sql("""
                UPDATE action_proposals SET status = :st, executed_at = now(), result = :r
                WHERE id = :id AND status = 'EXECUTING'
                """)
                .param("st", status).param("r", text).param("id", id).update() > 0;
    }

    private void escalate(long incidentId) {
        jdbc.sql("UPDATE incidents SET status = 'ESCALATED', updated_at = now() WHERE id = :i AND resolved_at IS NULL")
                .param("i", incidentId).update();
    }

    // The container name comes only from ServiceRegistry (checked in claim); the arguments are fixed.
    private String restart(String container) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("docker", "restart", container)
                .redirectErrorStream(true)
                .start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("docker restart timed out");
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (p.exitValue() != 0) {
            throw new IOException("docker restart failed: " + out);
        }
        return "restarted " + container;
    }

    // Simulated: images are not versioned here, so this restarts and records the rollback.
    private String rollback(String service) throws IOException, InterruptedException {
        List<String> versions = jdbc.sql("SELECT version FROM deployments WHERE service = :s ORDER BY deployed_at DESC LIMIT 2")
                .param("s", service).query(String.class).list();
        if (versions.size() < 2) {
            throw new IOException("no previous version recorded for " + service + "; cannot roll back");
        }
        String restarted = restart(service);
        jdbc.sql("INSERT INTO deployments (service, version, deployed_by, notes) VALUES (:s, :v, 'commander', :n)")
                .param("s", service).param("v", versions.get(1))
                .param("n", "rollback from " + versions.get(0) + " (simulated)")
                .update();
        return "rolled back " + service + " to " + versions.get(1) + " (simulated; " + restarted + ")";
    }
}