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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ActionExecutor {

    public enum Outcome {
        EXECUTED,
        FAILED,
        NOT_FOUND,
        NOT_APPROVED,
        COOLDOWN
    }

    public record Result(Outcome outcome, String message) {}

    private static final Logger log =
            LoggerFactory.getLogger(ActionExecutor.class);

    private static final Set<String> ALLOWED =
            Set.of("RESTART_SERVICE", "ROLLBACK_DEPLOY");

    private static final int COOLDOWN_MINUTES = 5;

    private final JdbcClient jdbc;
    private final ServiceRegistry services;
    private final Verifier verifier;

    public ActionExecutor(
            JdbcClient jdbc,
            ServiceRegistry services,
            Verifier verifier) {

        this.jdbc = jdbc;
        this.services = services;
        this.verifier = verifier;
    }

    public Result execute(long proposalId) {

        List<Map<String, Object>> rows =
                jdbc.sql("""
                        SELECT incident_id, action_type, target_service, status
                        FROM action_proposals
                        WHERE id = :id
                        """)
                        .param("id", proposalId)
                        .query()
                        .listOfRows();

        if (rows.isEmpty()) {
            return new Result(
                    Outcome.NOT_FOUND,
                    "no such proposal"
            );
        }

        Map<String, Object> row = rows.get(0);

        String status = (String) row.get("status");
        String type = (String) row.get("action_type");
        String service = (String) row.get("target_service");

        long incidentId =
                ((Number) row.get("incident_id")).longValue();

        if (!"APPROVED".equals(status)) {

            return new Result(
                    Outcome.NOT_APPROVED,
                    "proposal is " + status
                            + "; only APPROVED proposals can run"
            );
        }

        if (!ALLOWED.contains(type)
                || !services.known(service)) {

            mark(
                    proposalId,
                    "FAILED",
                    "blocked: action or service is not on the allow-list"
            );

            log.warn(
                    "Proposal {} blocked: {} on {} is not allowed",
                    proposalId,
                    type,
                    service
            );

            return new Result(
                    Outcome.FAILED,
                    "action or service is not on the allow-list"
            );
        }

        if (cooldownActive(service)) {

            return new Result(
                    Outcome.COOLDOWN,
                    service
                            + " was acted on in the last "
                            + COOLDOWN_MINUTES
                            + " minutes; try again later"
            );
        }

        /*
         * Atomic claim:
         * only one caller can move APPROVED -> EXECUTING.
         */
        int claimed =
                jdbc.sql("""
                        UPDATE action_proposals
                        SET status = 'EXECUTING'
                        WHERE id = :id
                          AND status = 'APPROVED'
                        """)
                        .param("id", proposalId)
                        .update();

        if (claimed == 0) {

            return new Result(
                    Outcome.NOT_APPROVED,
                    "proposal is already being executed"
            );
        }

        try {

            String message =
                    type.equals("RESTART_SERVICE")
                            ? restart(service)
                            : rollback(service);

            mark(
                    proposalId,
                    "EXECUTED",
                    message
            );

            jdbc.sql("""
                    UPDATE incidents
                    SET status = 'REMEDIATING',
                        updated_at = now()
                    WHERE id = :i
                      AND resolved_at IS NULL
                    """)
                    .param("i", incidentId)
                    .update();

            /*
             * The action has completed successfully.
             * Now schedule verification to check whether
             * the incident actually recovered.
             */
            verifier.scheduleVerification(
                    incidentId,
                    service
            );

            log.info(
                    "Proposal {} executed: {}",
                    proposalId,
                    message
            );

            return new Result(
                    Outcome.EXECUTED,
                    message
            );

        } catch (Exception e) {

            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            String message =
                    e.getMessage() == null
                            ? e.toString()
                            : e.getMessage();

            mark(
                    proposalId,
                    "FAILED",
                    message
            );

            log.error(
                    "Proposal {} failed: {}",
                    proposalId,
                    message
            );

            return new Result(
                    Outcome.FAILED,
                    message
            );
        }
    }

    private boolean cooldownActive(String service) {

        Long n =
                jdbc.sql("""
                        SELECT count(*)
                        FROM action_proposals
                        WHERE target_service = :s
                          AND status = 'EXECUTED'
                          AND executed_at > :since
                        """)
                        .param("s", service)
                        .param(
                                "since",
                                OffsetDateTime.now()
                                        .minusMinutes(COOLDOWN_MINUTES)
                        )
                        .query(Long.class)
                        .single();

        return n != null && n > 0;
    }

    private void mark(
            long id,
            String status,
            String result) {

        String text =
                result == null
                        ? null
                        : result.length() > 1000
                        ? result.substring(0, 1000)
                        : result;

        jdbc.sql("""
                UPDATE action_proposals
                SET status = :st,
                    executed_at = now(),
                    result = :r
                WHERE id = :id
                """)
                .param("st", status)
                .param("r", text)
                .param("id", id)
                .update();
    }

    /*
     * The container name comes only from ServiceRegistry
     * (checked above); the arguments are fixed.
     */
    private String restart(String container)
            throws IOException, InterruptedException {

        Process p =
                new ProcessBuilder(
                        "docker",
                        "restart",
                        container
                )
                        .redirectErrorStream(true)
                        .start();

        if (!p.waitFor(60, TimeUnit.SECONDS)) {

            p.destroyForcibly();

            throw new IOException(
                    "docker restart timed out"
            );
        }

        String out =
                new String(
                        p.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8
                ).trim();

        if (p.exitValue() != 0) {

            throw new IOException(
                    "docker restart failed: " + out
            );
        }

        return "restarted " + container;
    }

    /*
     * Simulated:
     * images are not versioned here, so this restarts
     * and records the rollback.
     */
    private String rollback(String service)
            throws IOException, InterruptedException {

        List<String> versions =
                jdbc.sql("""
                        SELECT version
                        FROM deployments
                        WHERE service = :s
                        ORDER BY deployed_at DESC
                        LIMIT 2
                        """)
                        .param("s", service)
                        .query(String.class)
                        .list();

        if (versions.size() < 2) {

            throw new IOException(
                    "no previous version recorded for "
                            + service
                            + "; cannot roll back"
            );
        }

        String restarted = restart(service);

        jdbc.sql("""
                INSERT INTO deployments
                    (service, version, deployed_by, notes)
                VALUES
                    (:s, :v, 'commander', :n)
                """)
                .param("s", service)
                .param("v", versions.get(1))
                .param(
                        "n",
                        "rollback from "
                                + versions.get(0)
                                + " (simulated)"
                )
                .update();

        return "rolled back "
                + service
                + " to "
                + versions.get(1)
                + " (simulated; "
                + restarted
                + ")";
    }
}