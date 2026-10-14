
package com.bhumi.commander.remediation;

import com.bhumi.commander.agent.RootCause;
import com.bhumi.commander.tools.ServiceRegistry;
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

    private static final Set<String> EXECUTABLE =
            Set.of("RESTART_SERVICE", "ROLLBACK_DEPLOY");

    private static final int MAX_PROPOSALS = 2;

    private final JdbcClient jdbc;
    private final ServiceRegistry services;

    public ProposalService(JdbcClient jdbc, ServiceRegistry services) {
        this.jdbc = jdbc;
        this.services = services;
    }

    /**
     * Creates PENDING proposals from the model's suggested actions.
     * Returns how many were created.
     */
    public int createFrom(long incidentId, RootCause rc) {
        int created = 0;

        String rationale = rc.summary().length() > 500
                ? rc.summary().substring(0, 500)
                : rc.summary();

        for (String action : rc.suggestedActions()) {
            if (created >= MAX_PROPOSALS) {
                break;
            }

            if (action == null) {
                continue;
            }

            String[] parts = action.trim().split("\\s+");

            if (parts.length < 2) {
                continue;
            }

            String type = parts[0].toUpperCase();
            String service = parts[1];

            // Only allow approved action types on known services.
            if (!EXECUTABLE.contains(type) || !services.known(service)) {
                continue;
            }

            created += jdbc.sql("""
                    INSERT INTO action_proposals
                        (incident_id, action_type, target_service, rationale)
                    VALUES (:i, :t, :s, :r)
                    ON CONFLICT (incident_id, action_type, target_service)
                    DO NOTHING
                    """)
                    .param("i", incidentId)
                    .param("t", type)
                    .param("s", service)
                    .param("r", rationale)
                    .update();
        }

        return created;
    }

    public Outcome decide(long id, boolean approve, String by) {

        // Find the proposal and its incident state.
        var current = jdbc.sql("""
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

        Map<String, Object> row = current.get(0);

        String currentStatus = String.valueOf(row.get("status"));

        if (!"PENDING".equals(currentStatus)) {
            return Outcome.ALREADY_DECIDED;
        }

        // Never approve a proposal for an already-resolved incident.
        if (approve && row.get("resolved_at") != null) {
            cancelPendingProposal(id, by);
            return Outcome.INCIDENT_RESOLVED;
        }

        int updated;

        if (approve) {
            // Also check incident state during the update to reduce race conditions.
            updated = jdbc.sql("""
                    UPDATE action_proposals p
                    SET status = 'APPROVED',
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
                    .param("by", by)
                    .param("id", id)
                    .update();
        } else {
            updated = jdbc.sql("""
                    UPDATE action_proposals
                    SET status = 'REJECTED',
                        decided_by = :by,
                        decided_at = now()
                    WHERE id = :id
                      AND status = 'PENDING'
                    """)
                    .param("by", by)
                    .param("id", id)
                    .update();
        }

        if (updated == 0) {
            // Re-check in case another request changed the proposal or incident.
            var latest = jdbc.sql("""
                    SELECT p.status, i.resolved_at
                    FROM action_proposals p
                    JOIN incidents i ON i.id = p.incident_id
                    WHERE p.id = :id
                    """)
                    .param("id", id)
                    .query()
                    .listOfRows();

            if (latest.isEmpty()) {
                return Outcome.NOT_FOUND;
            }

            Map<String, Object> latestRow = latest.get(0);

            if (approve
                    && latestRow.get("resolved_at") != null
                    && "PENDING".equals(
                    String.valueOf(latestRow.get("status")))) {
                cancelPendingProposal(id, by);
                return Outcome.INCIDENT_RESOLVED;
            }

            return Outcome.ALREADY_DECIDED;
        }

        if (!approve) {
            escalateIfAllRejected(id);
        }

        return Outcome.OK;
    }

    private void cancelPendingProposal(long id, String by) {
        jdbc.sql("""
                UPDATE action_proposals
                SET status = 'CANCELLED',
                    result = 'Cancelled because the incident was already resolved',
                    decided_by = :by,
                    decided_at = now()
                WHERE id = :id
                  AND status = 'PENDING'
                """)
                .param("by", by)
                .param("id", id)
                .update();
    }

    private void escalateIfAllRejected(long proposalId) {
        jdbc.sql("""
                UPDATE incidents
                SET status = 'ESCALATED',
                    updated_at = now()
                WHERE resolved_at IS NULL
                  AND id = (
                      SELECT incident_id
                      FROM action_proposals
                      WHERE id = :p
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM action_proposals
                      WHERE incident_id = (
                          SELECT incident_id
                          FROM action_proposals
                          WHERE id = :p
                      )
                      AND status <> 'REJECTED'
                  )
                """)
                .param("p", proposalId)
                .update();
    }
}