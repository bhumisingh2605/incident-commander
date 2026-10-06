package com.bhumi.commander.api;

import com.bhumi.commander.incident.IncidentService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final JdbcClient jdbc;
    private final IncidentService incidents;

    public IncidentController(JdbcClient jdbc, IncidentService incidents) {
        this.jdbc = jdbc;
        this.incidents = incidents;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) {
            return jdbc.sql("""
                    SELECT id, title, status, service, severity, rc_service, rc_category, rc_confidence,
                           created_at, resolved_at
                    FROM incidents ORDER BY id DESC LIMIT 50
                    """).query().listOfRows();
        }
        return jdbc.sql("""
                SELECT id, title, status, service, severity, rc_service, rc_category, rc_confidence,
                       created_at, resolved_at
                FROM incidents WHERE status = :status ORDER BY id DESC LIMIT 50
                """).param("status", status.toUpperCase()).query().listOfRows();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM incidents WHERE id = :id")
                .param("id", id).query().listOfRows();
        if (rows.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("alerts", jdbc.sql("""
                SELECT alertname, application, status, severity, summary, starts_at
                FROM alerts WHERE incident_id = :id ORDER BY id
                """).param("id", id).query().listOfRows());
        out.put("agent_runs", jdbc.sql("""
                SELECT run_id, outcome, tool_calls, seconds, started_at, finished_at, error
                FROM agent_runs WHERE incident_id = :id ORDER BY started_at DESC
                """).param("id", id).query().listOfRows());
        out.put("tool_calls", jdbc.sql("""
                SELECT run_id, tool_name, args, status, duration_ms
                FROM tool_calls
                WHERE run_id IN (SELECT run_id FROM agent_runs WHERE incident_id = :id)
                ORDER BY id
                """).param("id", id).query().listOfRows());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/investigate")
    public ResponseEntity<String> investigate(@PathVariable long id) {
        return incidents.reinvestigate(id)
                ? ResponseEntity.accepted().body("investigation queued for incident " + id)
                : ResponseEntity.notFound().build();
    }
}