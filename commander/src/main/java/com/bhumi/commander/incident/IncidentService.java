package com.bhumi.commander.incident;

import com.bhumi.commander.agent.AnalystAgent;
import com.bhumi.commander.agent.FastAnalyst;
import com.bhumi.commander.agent.RootCause;
import com.bhumi.commander.remediation.ProposalService;
import com.bhumi.commander.tools.ServiceRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class IncidentService implements DisposableBean {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentService.class);

    private static final String MODEL = "qwen3:8b";

    // Pause before the single retry when the model server cannot be reached.
    private static final long CONNECTION_RETRY_DELAY_SECONDS = 5;

    private final JdbcClient jdbc;
    private final FastAnalyst analyst;
    private final ServiceRegistry services;
    private final ProposalService proposals;
    private final long analysisTimeoutSeconds;

    // Run one local-model investigation at a time.
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "analyst-worker");
                t.setDaemon(true);
                return t;
            });

    // Runs the model call itself so the worker can enforce an overall deadline
    // and move on to the next incident if the model hangs.
    private final ExecutorService analysisPool =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "analysis-run");
                t.setDaemon(true);
                return t;
            });

    public IncidentService(
            JdbcClient jdbc,
            FastAnalyst analyst,
            ServiceRegistry services,
            ProposalService proposals,
            @Value("${commander.analysis-timeout-seconds:600}") long analysisTimeoutSeconds) {

        this.jdbc = jdbc;
        this.analyst = analyst;
        this.services = services;
        this.proposals = proposals;
        this.analysisTimeoutSeconds = analysisTimeoutSeconds;
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
        analysisPool.shutdownNow();
    }

    /**
     * Called for every firing alert after its alert row has been saved.
     */
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

    /**
     * Resolves an incident when all linked alerts clear,
     * except when it has been escalated for manual follow-up.
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

                int updated = jdbc.sql("""
                        UPDATE incidents
                        SET status = 'RESOLVED',
                            resolved_at = now(),
                            updated_at = now()
                        WHERE id = :i
                          AND resolved_at IS NULL
                          AND status <> 'ESCALATED'
                        """)
                        .param("i", incidentId)
                        .update();

                if (updated > 0) {

                    int cancelled = jdbc.sql("""
                            UPDATE action_proposals
                            SET status = 'CANCELLED',
                                result = 'Cancelled because the incident was resolved before approval'
                            WHERE incident_id = :i
                              AND status = 'PENDING'
                            """)
                            .param("i", incidentId)
                            .update();

                    log.info(
                            "Incident {} resolved: all alerts cleared; cancelled {} pending proposals",
                            incidentId,
                            cancelled
                    );
                } else {
                    log.info(
                            "Incident {} was not auto-resolved because it is already resolved or requires escalation",
                            incidentId
                    );
                }
            }
        }
    }

    /**
     * Manually reruns an investigation.
     * Returns false when the incident does not exist or is resolved.
     */
    public boolean reinvestigate(long incidentId) {

        List<String> titles = jdbc.sql("""
                SELECT title
                FROM incidents
                WHERE id = :i
                  AND resolved_at IS NULL
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

        String runId = UUID.randomUUID()
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
             * Gather evidence and obtain the root-cause analysis.
             */
            AnalystAgent.Result result =
                    analyzeWithDeadline(runId, alertText);

            RootCause rc = result.rootCause();

            if (rc == null) {
                throw new IllegalStateException(
                        "Analyst returned no root-cause result"
                );
            }

            /*
             * Update only incidents that are still unresolved.
             * Preserve statuses changed by another process.
             */
            int updated = jdbc.sql("""
                    UPDATE incidents SET
                        rc_summary = :sum,
                        rc_service = :svc,
                        rc_category = :cat,
                        rc_confidence = :conf,
                        rc_evidence = :ev,
                        rc_actions = :act,
                        status = CASE
                            WHEN status <> 'INVESTIGATING' THEN status
                            WHEN UPPER(COALESCE(:cat, 'UNKNOWN')) = 'UNKNOWN'
                                THEN 'ESCALATED'
                            ELSE 'DIAGNOSED'
                        END,
                        updated_at = now()
                    WHERE id = :id
                      AND resolved_at IS NULL
                    """)
                    .param("sum", rc.summary())
                    .param("svc", rc.suspectedService())
                    .param("cat", rc.category())
                    .param("conf", rc.confidence())
                    .param("ev", String.join("\n", rc.evidence()))
                    .param("act", String.join("\n", rc.suggestedActions()))
                    .param("id", incidentId)
                    .update();

            /*
             * If the incident was resolved while analysis was running,
             * do not create proposals or update its diagnosis.
             */
            if (updated == 0) {

                log.info(
                        "Incident {} was already resolved or missing; skipping RCA follow-up for run {}",
                        incidentId,
                        runId
                );

                markRunSuccessful(result, runId);
                return;
            }

            /*
             * Create remediation proposals from the RCA.
             */
            try {

                int created = proposals.createFrom(incidentId, rc);

                if (created > 0) {

                    jdbc.sql("""
                            UPDATE incidents
                            SET status = 'AWAITING_APPROVAL',
                                updated_at = now()
                            WHERE id = :id
                              AND status = 'DIAGNOSED'
                              AND resolved_at IS NULL
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

            markRunSuccessful(result, runId);

            log.info(
                    "Incident {} analysis completed: {} ({})",
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

            /*
             * Escalate only if the incident is still investigating.
             * Do not overwrite a status changed by another process.
             */
            jdbc.sql("""
                    UPDATE incidents
                    SET status = 'ESCALATED',
                        rc_summary = COALESCE(NULLIF(rc_summary, ''), :msg),
                        updated_at = now()
                    WHERE id = :i
                      AND status = 'INVESTIGATING'
                      AND resolved_at IS NULL
                    """)
                    .param("i", incidentId)
                    .param("msg", failureSummary(e, runId))
                    .update();
        }
    }

    /**
     * Runs the analyst with an overall deadline. If the model server cannot
     * be reached, retries once after a short pause. Any other failure, or a
     * timeout, propagates so the caller escalates the incident to a human.
     */
    private AnalystAgent.Result analyzeWithDeadline(
            String runId,
            String alertText) throws Exception {

        try {
            return analyzeOnce(runId, alertText);
        } catch (Exception e) {

            if (!isConnectionFailure(e)) {
                throw e;
            }

            log.warn(
                    "Analyst run {}: model unreachable ({}); retrying once in {}s",
                    runId,
                    e.toString(),
                    CONNECTION_RETRY_DELAY_SECONDS
            );

            TimeUnit.SECONDS.sleep(CONNECTION_RETRY_DELAY_SECONDS);

            return analyzeOnce(runId, alertText);
        }
    }

    private AnalystAgent.Result analyzeOnce(
            String runId,
            String alertText) throws Exception {

        Future<AnalystAgent.Result> future =
                analysisPool.submit(() -> analyst.analyze(runId, alertText));

        try {
            return future.get(analysisTimeoutSeconds, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            // The abandoned call is bounded by the HTTP read timeout; its
            // result is discarded, so it cannot update the incident later.
            future.cancel(true);
            throw new IllegalStateException(
                    "Analysis timed out after " + analysisTimeoutSeconds + "s");

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;

        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }

    private static boolean isConnectionFailure(Throwable t) {

        for (Throwable c = t; c != null; c = c.getCause()) {

            if (c instanceof java.net.ConnectException
                    || c instanceof java.net.http.HttpConnectTimeoutException) {
                return true;
            }

            if (c.getCause() == c) {
                break;
            }
        }

        return false;
    }

    /** Operator-facing reason shown on the incident; full detail stays in agent_runs. */
    private static String failureSummary(Exception e, String runId) {

        String reason;

        if (isConnectionFailure(e)) {
            reason = "the AI model could not be reached";
        } else if (e instanceof IllegalStateException
                && String.valueOf(e.getMessage()).startsWith("Analysis timed out")) {
            reason = e.getMessage().toLowerCase();
        } else {
            reason = "an unexpected error occurred";
        }

        return "Automated analysis could not complete: " + reason
                + " (run " + runId + "). Manual investigation required.";
    }

    private void markRunSuccessful(
            AnalystAgent.Result result,
            String runId) {

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
    }

    private Long findOpenRelatedIncident(String application) {

        List<Map<String, Object>> open = jdbc.sql("""
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
                return ((Number) row.get("id")).longValue();
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