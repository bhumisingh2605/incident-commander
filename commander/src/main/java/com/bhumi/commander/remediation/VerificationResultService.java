
package com.bhumi.commander.remediation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerificationResultService {

    private static final Logger log =
            LoggerFactory.getLogger(VerificationResultService.class);

    private final JdbcClient jdbc;

    public VerificationResultService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void finish(long incidentId, boolean ok, String text) {

        jdbc.sql("""
                UPDATE incidents
                SET verification = :v,
                    verified_at = now(),
                    updated_at = now()
                WHERE id = :i
                """)
                .param("v", text)
                .param("i", incidentId)
                .update();

        if (ok) {
            int resolved = jdbc.sql("""
                    UPDATE incidents
                    SET status = 'RESOLVED',
                        resolved_at = now(),
                        updated_at = now()
                    WHERE id = :i
                      AND resolved_at IS NULL
                    """)
                    .param("i", incidentId)
                    .update();

            if (resolved > 0) {
                int cancelled = jdbc.sql("""
                        UPDATE action_proposals
                        SET status = 'CANCELLED',
                            result = 'Cancelled because verification resolved the incident',
                            decided_at = now()
                        WHERE incident_id = :i
                          AND status = 'PENDING'
                        """)
                        .param("i", incidentId)
                        .update();

                log.info(
                        "Incident {} resolved by verification; cancelled {} pending proposals",
                        incidentId,
                        cancelled
                );
            }

        } else {
            jdbc.sql("""
                    UPDATE incidents
                    SET status = 'ESCALATED',
                        updated_at = now()
                    WHERE id = :i
                      AND resolved_at IS NULL
                    """)
                    .param("i", incidentId)
                    .update();
        }
    }
}
