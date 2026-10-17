package com.bhumi.commander.incident;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Keeps stored alerts in line with what Prometheus says is firing, so a missed resolve cannot freeze an incident. */
@Component
public class AlertReconciler {

    private static final Logger log = LoggerFactory.getLogger(AlertReconciler.class);
    private static final int GRACE_SECONDS = 120;

    private final JdbcClient jdbc;
    private final String prometheusUrl;
    private final RestClient http = RestClient.create();

    public AlertReconciler(JdbcClient jdbc,
                           @Value("${commander.prometheus-url:http://localhost:9090}") String prometheusUrl) {
        this.jdbc = jdbc;
        this.prometheusUrl = prometheusUrl;
    }

    @Scheduled(initialDelay = 90_000, fixedDelay = 60_000)
    public void reconcile() {
        try {
            run();
        } catch (Exception e) {
            log.warn("Alert reconciliation failed: {}", e.toString());
        }
    }

    private void run() {
        Set<String> active = activeInPrometheus();
        if (active == null) return; // Prometheus unreachable: change nothing

        List<Map<String, Object>> firing = jdbc.sql("""
                SELECT id, alertname, application FROM alerts
                WHERE status = 'firing' AND updated_at < :cutoff
                """)
                .param("cutoff", OffsetDateTime.now().minusSeconds(GRACE_SECONDS))
                .query().listOfRows();

        int resolved = 0;
        for (Map<String, Object> row : firing) {
            String key = row.get("alertname") + "|" + row.get("application");
            if (active.contains(key)) continue;
            resolved += jdbc.sql("UPDATE alerts SET status = 'resolved', updated_at = now() WHERE id = :id AND status = 'firing'")
                    .param("id", ((Number) row.get("id")).longValue()).update();
        }

        int closed = jdbc.sql("""
                UPDATE incidents SET resolved_at = now(), updated_at = now(),
                  status = CASE WHEN status = 'ESCALATED' THEN 'ESCALATED' ELSE 'RESOLVED' END
                WHERE resolved_at IS NULL
                  AND status <> 'REMEDIATING'
                  AND EXISTS (SELECT 1 FROM alerts a WHERE a.incident_id = incidents.id)
                  AND NOT EXISTS (SELECT 1 FROM alerts a WHERE a.incident_id = incidents.id AND a.status <> 'resolved')
                """).update();

        int expired = jdbc.sql("""
                UPDATE action_proposals SET status = 'EXPIRED'
                WHERE status = 'PENDING'
                  AND incident_id IN (SELECT id FROM incidents WHERE resolved_at IS NOT NULL)
                """).update();

        if (resolved + closed + expired > 0) {
            log.info("Reconciler: {} stale alert(s) resolved, {} incident(s) closed, {} proposal(s) expired",
                    resolved, closed, expired);
        }
    }

    /** Keys of the form alertname|application for alerts Prometheus reports as firing; null if unknown. */
    private Set<String> activeInPrometheus() {
        try {
            Map<?, ?> body = http.get().uri(prometheusUrl + "/api/v1/alerts").retrieve().body(Map.class);
            if (body == null || !(body.get("data") instanceof Map<?, ?> data)
                    || !(data.get("alerts") instanceof List<?> alerts)) {
                return null;
            }
            Set<String> keys = new HashSet<>();
            for (Object o : alerts) {
                if (!(o instanceof Map<?, ?> a)) continue;
                if (!"firing".equals(String.valueOf(a.get("state")))) continue;
                if (!(a.get("labels") instanceof Map<?, ?> labels)) continue;
                Object app = labels.get("application") != null ? labels.get("application") : labels.get("job");
                keys.add(labels.get("alertname") + "|" + app);
            }
            return keys;
        } catch (Exception e) {
            log.warn("Reconciler could not reach Prometheus: {}", e.toString());
            return null;
        }
    }
}