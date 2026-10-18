package com.bhumi.commander.remediation;

import com.bhumi.commander.agent.RootCause;
import com.bhumi.commander.tools.ServiceRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ProposalService {

    public enum Outcome {
        OK,
        NOT_FOUND,
        ALREADY_DECIDED,
        INCIDENT_RESOLVED
    }

    private static final Set<String> ALLOWED_ACTIONS =
            Set.of("RESTART_SERVICE", "ROLLBACK_DEPLOY");

    private static final int MAX_PROPOSALS = 2;

    private final JdbcClient jdbc;
    private final ServiceRegistry services;

    public ProposalService(
            JdbcClient jdbc,
            ServiceRegistry services) {
        this.jdbc = jdbc;
        this.services = services;
    }

    public int createFrom(long incidentId, RootCause rc) {
        if (rc == null
                || rc.category() == null
                || "UNKNOWN".equalsIgnoreCase(rc.category())
                || rc.suspectedService() == null
                || rc.suspectedService().isBlank()
                || rc.suggestedActions() == null) {
            return 0;
        }

        // Do not create proposals for missing or resolved incidents.
        Long unresolved = jdbc.sql("""
                SELECT count(*)
                FROM incidents
                WHERE id = :i
                  AND resolved_at IS NULL
                """)
                .param("i", incidentId)
                .query(Long.class)
                .single();

        if (unresolved == null || unresolved == 0) {
            return 0;
        }

        // Count active proposals for this incident.
        Long existing = jdbc.sql("""
                SELECT count(*)
                FROM action_proposals
                WHERE incident_id = :i
                  AND status IN ('PENDING', 'APPROVED', 'EXECUTING')
                """)
                .param("i", incidentId)
                .query(Long.class)
                .single();

        int remaining = MAX_PROPOSALS
                - (existing == null ? 0 : existing.intValue());

        if (remaining <= 0) {
            return 0;
        }

        int created = 0;

        for (String suggested : rc.suggestedActions()) {
            if (created >= remaining) {
                break;
            }

            if (suggested == null || suggested.isBlank()) {
                continue;
            }

            // Accept either:
            // "RESTART_SERVICE"
            // "RESTART_SERVICE order-service"
            String[] parts = suggested.trim().split("\\s+", 2);

            String action = parts[0].toUpperCase(Locale.ROOT);

            // If no explicit target is supplied, use the diagnosed service.
            String target = parts.length == 2
                    ? parts[1].trim()
                    : rc.suspectedService().trim();

            // Only explicitly permitted actions are accepted.
            if (!ALLOWED_ACTIONS.contains(action)) {
                continue;
            }

            // Never accept an unknown service.
            if (target.isBlank() || !services.known(target)) {
                continue;
            }

            // Insert only if the incident is still unresolved and
            // an equivalent active proposal does not already exist.
            int inserted = jdbc.sql("""
                    INSERT INTO action_proposals
                        (incident_id, action_type, target_service, status)
                    SELECT :i, :a, :s, 'PENDING'
                    WHERE EXISTS (
                        SELECT 1
                        FROM incidents
                        WHERE id = :i
                          AND resolved_at IS NULL
                    )
                    AND NOT EXISTS (
                        SELECT 1
                        FROM action_proposals
                        WHERE incident_id = :i
                          AND action_type = :a
                          AND target_service = :s
                          AND status IN (
                              'PENDING', 'APPROVED', 'EXECUTING'
                          )
                    )
                    """)
                    .param("i", incidentId)
                    .param("a", action)
                    .param("s", target)
                    .update();

            created += inserted;
        }

        return created;
    }

    public Outcome decide(long id, boolean approve, String by) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT p.status, i.resolved_at
                FROM action_proposals p
                JOIN incidents i ON i.id = p.incident_id
                WHERE p.id = :id
                """)
                .param("id", id)
                .query()
                .listOfRows();

        if (rows.isEmpty()) {
            return Outcome.NOT_FOUND;
        }

        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");

        if (!"PENDING".equals(status)) {
            return Outcome.ALREADY_DECIDED;
        }

        // A resolved incident must not have a pending proposal approved.
        if (row.get("resolved_at") != null) {
            cancelPendingProposal(
                    id,
                    by,
                    "Cancelled because the incident is already resolved");
            return Outcome.INCIDENT_RESOLVED;
        }

        String nextStatus = approve ? "APPROVED" : "REJECTED";

        // Atomically verify that the proposal is still pending and
        // the incident remains unresolved.
        int updated = jdbc.sql("""
                UPDATE action_proposals p
                SET status = :st,
                    decided_by = :by,
                    decided_at = now()
                WHERE p.id = :id
                  AND p.status = 'PENDING'
                  AND EXISTS (
                      SELECT 1
                      FROM incidents i
                      WHERE i.id = p.incident_id
                        AND i.resolved_at IS NULL
                  )
                """)
                .param("st", nextStatus)
                .param("by", by)
                .param("id", id)
                .update();

        if (updated == 1) {
            return Outcome.OK;
        }

        // Recheck after a concurrent state change.
        List<Map<String, Object>> current = jdbc.sql("""
                SELECT p.status, i.resolved_at
                FROM action_proposals p
                JOIN incidents i ON i.id = p.incident_id
                WHERE p.id = :id
                """)
                .param("id", id)
                .query()
                .listOfRows();

        if (current.isEmpty()) {
            return Outcome.NOT_FOUND;
        }

        Map<String, Object> latest = current.get(0);

        if (latest.get("resolved_at") != null
                && "PENDING".equals(latest.get("status"))) {
            cancelPendingProposal(
                    id,
                    by,
                    "Cancelled because the incident is already resolved");
            return Outcome.INCIDENT_RESOLVED;
        }

        return Outcome.ALREADY_DECIDED;
    }

    private void cancelPendingProposal(
            long id,
            String by,
            String reason) {
        jdbc.sql("""
                UPDATE action_proposals
                SET status = 'CANCELLED',
                    result = :r,
                    decided_by = :by,
                    decided_at = now()
                WHERE id = :id
                  AND status = 'PENDING'
                """)
                .param("r", reason)
                .param("by", by)
                .param("id", id)
                .update();
    }
}
