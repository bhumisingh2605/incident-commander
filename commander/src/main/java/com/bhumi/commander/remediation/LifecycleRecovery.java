package com.bhumi.commander.remediation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Brings proposals and incidents back to a consistent state after crashes, timeouts and abandoned approvals. */
@Component
public class LifecycleRecovery {

    private static final Logger log = LoggerFactory.getLogger(LifecycleRecovery.class);
    private static final int STALE_EXECUTING_MINUTES = 3;   // docker restart times out after 1 minute
    private static final int APPROVAL_EXPIRY_MINUTES = 15;
    private static final int PENDING_EXPIRY_MINUTES = 60;

    private final JdbcClient jdbc;
    private final Verifier verifier;

    public LifecycleRecovery(JdbcClient jdbc, Verifier verifier) {
        this.jdbc = jdbc;
        this.verifier = verifier;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            // Nothing can be running in a process that just started, so every EXECUTING row is left over.
            int interrupted = interruptExecuting(OffsetDateTime.now());
            int requeued = requeueVerifications();
            log.info("Recovery at startup: {} stuck EXECUTING proposal(s) interrupted, {} verification(s) re-queued",
                    interrupted, requeued);
        } catch (Exception e) {
            log.warn("Recovery at startup failed: {}", e.toString());
        }
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 60_000)
    public void sweep() {
        try {
            int interrupted = interruptExecuting(OffsetDateTime.now().minusMinutes(STALE_EXECUTING_MINUTES));
            int expired = expireOld();
            if (interrupted + expired > 0) {
                log.info("Recovery sweep: {} proposal(s) interrupted, {} expired", interrupted, expired);
            }
        } catch (Exception e) {
            log.warn("Recovery sweep failed: {}", e.toString());
        }
    }

    private int interruptExecuting(OffsetDateTime cutoff) {
        List<Long> incidents = jdbc.sql("""
                UPDATE action_proposals SET status = 'INTERRUPTED', executed_at = now(),
                  result = 'Interrupted: the commander stopped or timed out while this action was running. The outcome is unknown, so check the service manually. It is never retried automatically.'
                WHERE status = 'EXECUTING' AND (claimed_at IS NULL OR claimed_at < :cutoff)
                RETURNING incident_id
                """)
                .param("cutoff", cutoff).query(Long.class).list();
        for (Long id : incidents) {
            jdbc.sql("UPDATE incidents SET status = 'ESCALATED', updated_at = now() WHERE id = :i AND resolved_at IS NULL")
                    .param("i", id).update();
        }
        return incidents.size();
    }

    private int expireOld() {
        int approved = jdbc.sql("""
                UPDATE action_proposals SET status = 'EXPIRED', result = 'Approval expired before the action ran.'
                WHERE status = 'APPROVED' AND decided_at < :cutoff
                """)
                .param("cutoff", OffsetDateTime.now().minusMinutes(APPROVAL_EXPIRY_MINUTES)).update();
        int pending = jdbc.sql("""
                UPDATE action_proposals SET status = 'EXPIRED', result = 'No decision was made in time.'
                WHERE status = 'PENDING' AND created_at < :cutoff
                """)
                .param("cutoff", OffsetDateTime.now().minusMinutes(PENDING_EXPIRY_MINUTES)).update();
        // An incident with no live proposal left would wait for approval forever.
        jdbc.sql("""
                UPDATE incidents SET status = 'ESCALATED', updated_at = now()
                WHERE status = 'AWAITING_APPROVAL' AND resolved_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM action_proposals p WHERE p.incident_id = incidents.id
                                  AND p.status IN ('PENDING', 'APPROVED', 'EXECUTING'))
                """).update();
        return approved + pending;
    }

    private int requeueVerifications() {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT i.id AS incident_id,
                       (SELECT p.target_service FROM action_proposals p
                         WHERE p.incident_id = i.id AND p.status = 'EXECUTED'
                         ORDER BY p.executed_at DESC LIMIT 1) AS target
                FROM incidents i
                WHERE i.status = 'REMEDIATING' AND i.resolved_at IS NULL AND i.verification IS NULL
                """).query().listOfRows();
        int n = 0;
        for (Map<String, Object> row : rows) {
            if (row.get("target") == null) continue;
            verifier.scheduleVerification(((Number) row.get("incident_id")).longValue(), (String) row.get("target"));
            n++;
        }
        return n;
    }
}