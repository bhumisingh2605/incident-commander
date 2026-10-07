package com.bhumi.commander.remediation;

import com.bhumi.commander.agent.RootCause;
import com.bhumi.commander.tools.ServiceRegistry;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ProposalService {

    public enum Outcome { OK, NOT_FOUND, ALREADY_DECIDED }

    private static final Set<String> EXECUTABLE = Set.of("RESTART_SERVICE", "ROLLBACK_DEPLOY");
    private static final int MAX_PROPOSALS = 2;

    private final JdbcClient jdbc;
    private final ServiceRegistry services;

    public ProposalService(JdbcClient jdbc, ServiceRegistry services) {
        this.jdbc = jdbc;
        this.services = services;
    }

    /** Creates PENDING proposals from the model's suggested actions. Returns how many were created. */
    public int createFrom(long incidentId, RootCause rc) {
        int created = 0;
        String rationale = rc.summary().length() > 500 ? rc.summary().substring(0, 500) : rc.summary();
        for (String action : rc.suggestedActions()) {
            if (created >= MAX_PROPOSALS) break;
            if (action == null) continue;
            String[] parts = action.trim().split("\\s+");
            if (parts.length < 2) continue;
            String type = parts[0].toUpperCase();
            String service = parts[1];
            // Model output is untrusted: only allow-listed actions on known services become proposals.
            if (!EXECUTABLE.contains(type) || !services.known(service)) continue;

            created += jdbc.sql("""
                    INSERT INTO action_proposals (incident_id, action_type, target_service, rationale)
                    VALUES (:i, :t, :s, :r)
                    ON CONFLICT (incident_id, action_type, target_service) DO NOTHING
                    """)
                    .param("i", incidentId).param("t", type).param("s", service).param("r", rationale)
                    .update();
        }
        return created;
    }

    public Outcome decide(long id, boolean approve, String by) {
        int n = jdbc.sql("""
                UPDATE action_proposals
                SET status = :status, decided_by = :by, decided_at = now()
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("status", approve ? "APPROVED" : "REJECTED")
                .param("by", by).param("id", id)
                .update();

        if (n == 0) {
            Long exists = jdbc.sql("SELECT count(*) FROM action_proposals WHERE id = :id")
                    .param("id", id).query(Long.class).single();
            return exists != null && exists > 0 ? Outcome.ALREADY_DECIDED : Outcome.NOT_FOUND;
        }
        if (!approve) escalateIfAllRejected(id);
        return Outcome.OK;
    }

    private void escalateIfAllRejected(long proposalId) {
        jdbc.sql("""
                UPDATE incidents SET status = 'ESCALATED', updated_at = now()
                WHERE resolved_at IS NULL
                  AND id = (SELECT incident_id FROM action_proposals WHERE id = :p)
                  AND NOT EXISTS (
                    SELECT 1 FROM action_proposals
                    WHERE incident_id = (SELECT incident_id FROM action_proposals WHERE id = :p)
                      AND status <> 'REJECTED')
                """)
                .param("p", proposalId).update();
    }
}