package com.bhumi.commander.incident;

import com.bhumi.commander.agent.AnalystAgent;
import com.bhumi.commander.agent.FastAnalyst;
import com.bhumi.commander.agent.RootCause;
import com.bhumi.commander.remediation.ProposalService;
import com.bhumi.commander.tools.ServiceRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class IncidentService implements DisposableBean {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentService.class);

    private static final String MODEL = "qwen3:8b";

    private final JdbcClient jdbc;
    private final FastAnalyst analyst;
    private final ServiceRegistry services;
    private final ProposalService proposals;

    // One agent run at a time:
    // the local model cannot serve parallel investigations reliably.
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "analyst-worker");
                t.setDaemon(true);
                return t;
            });

    public IncidentService(
            JdbcClient jdbc,
            FastAnalyst analyst,
            ServiceRegistry services,
            ProposalService proposals) {

        this.jdbc = jdbc;
        this.analyst = analyst;
        this.services = services;
        this.proposals = proposals;
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
    }

    /** Called for every firing alert, after the alert row has been saved. */
    public void onAlertFiring(
            String fingerprint,
            String alertname,
            String application,
            String severity,
            String summary) {

        Long incidentId = findOpenRelatedIncident(application);
        boolean created = false;

        if (incidentId == null) {

            incidentId = jdbc.sql("""
                    INSERT INTO incidents (title, status, service, severity)
                    VALUES (:title, 'OPEN', :service, :severity)
                    RETURNING id
                    """)
                    .param("title", alertname + " on " + application)
                    .param("service", application)
                    .param("severity", severity)
                    .query(Long.class)
                    .single();

            created = true;

            log.info(
                    "Incident {} created for {} on {}",
                    incidentId,
                    alertname,
                    application
            );

        } else {

            log.info(
                    "Alert {} on {} joined open incident {}",
                    alertname,
                    application,
                    incidentId
            );
        }

        jdbc.sql("""
                UPDATE alerts
                SET incident_id = :i
                WHERE fingerprint = :fp
                """)
                .param("i", incidentId)
                .param("fp", fingerprint)
                .update();

        if (created) {

            startInvestigation(
                    incidentId,
                    "%s firing on %s: %s"
                            .formatted(alertname, application, summary)
            );
        }
    }

    /** Called for every resolved alert.
     * The incident resolves when none of its alerts are still firing.
     */
    public void onAlertResolved(String fingerprint) {

        List<Long> linked = jdbc.sql("""
                SELECT incident_id
                FROM alerts
                WHERE fingerprint = :fp
                  AND incident_id IS NOT NULL
                """)
                .param("fp", fingerprint)
                .query(Long.class)
                .list();

        for (Long incidentId : linked) {

            long stillFiring = jdbc.sql("""
                    SELECT count(*)
                    FROM alerts
                    WHERE incident_id = :i
                      AND status <> 'resolved'
                    """)
                    .param("i", incidentId)
                    .query(Long.class)
                    .single();

            if (stillFiring == 0) {

                int n = jdbc.sql("""
                        UPDATE incidents
                        SET status = 'RESOLVED',
                            resolved_at = now(),
                            updated_at = now()
                        WHERE id = :i
                          AND resolved_at IS NULL
                        """)
                        .param("i", incidentId)
                        .update();

                if (n > 0) {
                    log.info(
                            "Incident {} resolved: all alerts cleared",
                            incidentId
                    );
                }
            }
        }
    }

    /** Manual re-run.
     * Returns false when the incident does not exist.
     */
    public boolean reinvestigate(long incidentId) {

        List<String> titles = jdbc.sql("""
                SELECT title
                FROM incidents
                WHERE id = :i
                """)
                .param("i", incidentId)
                .query(String.class)
                .list();

        if (titles.isEmpty()) {
            return false;
        }

        startInvestigation(
                incidentId,
                titles.get(0) + " (manual re-run)"
        );

        return true;
    }

    private void startInvestigation(
            long incidentId,
            String alertText) {

        String runId =
                UUID.randomUUID()
                        .toString()
                        .substring(0, 8);

        jdbc.sql("""
                INSERT INTO agent_runs
                    (run_id, incident_id, model)
                VALUES
                    (:r, :i, :m)
                """)
                .param("r", runId)
                .param("i", incidentId)
                .param("m", MODEL)
                .update();

        jdbc.sql("""
                UPDATE incidents
                SET status = 'INVESTIGATING',
                    updated_at = now()
                WHERE id = :i
                  AND resolved_at IS NULL
                """)
                .param("i", incidentId)
                .update();

        worker.submit(
                () -> runAnalysis(
                        incidentId,
                        runId,
                        alertText
                )
        );
    }

    private void runAnalysis(
            long incidentId,
            String runId,
            String alertText) {

        try {

            /*
             * FastAnalyst is now used instead of AnalystAgent
             * for the actual investigation.
             *
             * It collects the evidence in Java and performs
             * one LLM conclusion call.
             */
            AnalystAgent.Result result =
                    analyst.analyze(runId, alertText);

            RootCause rc = result.rootCause();

            /*
             * First save the RCA diagnosis.
             */
            jdbc.sql("""
                    UPDATE incidents SET
                      rc_summary = :sum,
                      rc_service = :svc,
                      rc_category = :cat,
                      rc_confidence = :conf,
                      rc_evidence = :ev,
                      rc_actions = :act,
                      status = CASE
                          WHEN status = 'INVESTIGATING'
                          THEN 'DIAGNOSED'
                          ELSE status
                      END,
                      updated_at = now()
                    WHERE id = :id
                    """)
                    .param("sum", rc.summary())
                    .param("svc", rc.suspectedService())
                    .param("cat", rc.category())
                    .param("conf", rc.confidence())
                    .param(
                            "ev",
                            String.join(
                                    "\n",
                                    rc.evidence()
                            )
                    )
                    .param(
                            "act",
                            String.join(
                                    "\n",
                                    rc.suggestedActions()
                            )
                    )
                    .param("id", incidentId)
                    .update();

            /*
             * Create remediation proposals from the diagnosed RCA.
             *
             * Proposal creation is deliberately isolated from the
             * main analysis flow. If proposal creation fails, the
             * successful diagnosis must NOT become ESCALATED.
             */
            try {

                int created =
                        proposals.createFrom(incidentId, rc);

                if (created > 0) {

                    jdbc.sql("""
                            UPDATE incidents
                            SET status = 'AWAITING_APPROVAL',
                                updated_at = now()
                            WHERE id = :id
                              AND status = 'DIAGNOSED'
                            """)
                            .param("id", incidentId)
                            .update();
                }

            } catch (Exception e) {

                log.warn(
                        "Could not create proposals for incident {}: {}",
                        incidentId,
                        e.toString()
                );
            }

            /*
             * Mark the agent run as successful.
             */
            jdbc.sql("""
                    UPDATE agent_runs
                    SET finished_at = now(),
                        outcome = 'SUCCESS',
                        tool_calls = :tc,
                        seconds = :s,
                        findings = :f
                    WHERE run_id = :r
                    """)
                    .param("tc", result.toolCalls())
                    .param("s", result.seconds())
                    .param("f", result.findings())
                    .param("r", runId)
                    .update();

            log.info(
                    "Incident {} diagnosed: {} ({})",
                    incidentId,
                    rc.suspectedService(),
                    rc.category()
            );

        } catch (Exception e) {

            log.error(
                    "Analyst run {} failed for incident {}: {}",
                    runId,
                    incidentId,
                    e.toString()
            );

            String error = String.valueOf(e);

            jdbc.sql("""
                    UPDATE agent_runs
                    SET finished_at = now(),
                        outcome = 'FAILED',
                        error = :e
                    WHERE run_id = :r
                    """)
                    .param(
                            "e",
                            error.length() > 1000
                                    ? error.substring(0, 1000)
                                    : error
                    )
                    .param("r", runId)
                    .update();

            jdbc.sql("""
                    UPDATE incidents
                    SET status = 'ESCALATED',
                        updated_at = now()
                    WHERE id = :i
                      AND status = 'INVESTIGATING'
                    """)
                    .param("i", incidentId)
                    .update();
        }
    }

    private Long findOpenRelatedIncident(
            String application) {

        List<Map<String, Object>> open =
                jdbc.sql("""
                        SELECT id, service
                        FROM incidents
                        WHERE resolved_at IS NULL
                        ORDER BY id DESC
                        """)
                        .query()
                        .listOfRows();

        for (Map<String, Object> row : open) {

            if (related(
                    (String) row.get("service"),
                    application
            )) {

                return ((Number) row.get("id"))
                        .longValue();
            }
        }

        return null;
    }

    private boolean related(String a, String b) {

        if (a == null || b == null) {
            return false;
        }

        return a.equals(b)
                || services.downstreamOf(a).contains(b)
                || services.upstreamOf(a).contains(b);
    }
}