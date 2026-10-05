package com.bhumi.commander.api;

import com.bhumi.commander.dto.AlertmanagerPayload;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alerts")
public class AlertWebhookController {
    private static final Logger log = LoggerFactory.getLogger(AlertWebhookController.class);
    private final JdbcClient jdbc;

    public AlertWebhookController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody AlertmanagerPayload payload) {
        for (var a : payload.alerts()) {
            String name = a.labels().getOrDefault("alertname", "unknown");
            String app = a.labels().getOrDefault("application", a.labels().get("job"));
            String severity = a.labels().get("severity");
            String summary = a.annotations() == null ? null : a.annotations().get("summary");
            OffsetDateTime starts = a.startsAt() == null ? null : OffsetDateTime.parse(a.startsAt());

            jdbc.sql("""
                INSERT INTO alerts (fingerprint, alertname, application, severity, summary, status, starts_at)
                VALUES (:fp, :name, :app, :sev, :sum, :status, :starts)
                ON CONFLICT (fingerprint) DO UPDATE
                  SET status = EXCLUDED.status, summary = EXCLUDED.summary, updated_at = now()
                """)
                    .param("fp", a.fingerprint()).param("name", name).param("app", app)
                    .param("sev", severity).param("sum", summary)
                    .param("status", a.status()).param("starts", starts)
                    .update();

            log.info("ALERT {} [{}] on {}: {}", name, a.status(), app, summary);
        }
        return ResponseEntity.ok().build();
    }
}