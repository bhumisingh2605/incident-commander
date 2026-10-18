package com.bhumi.commander.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditEventController {

    private static final Set<String> SUBJECTS = Set.of("PROPOSAL", "INCIDENT");
    private final JdbcClient jdbc;

    public AuditEventController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/events")
    public ResponseEntity<?> events(@RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(required = false) String subject,
                                    @RequestParam(required = false) Long id) {
        if (subject != null && !SUBJECTS.contains(subject.toUpperCase())) {
            return ResponseEntity.badRequest().body("subject must be PROPOSAL or INCIDENT");
        }
        // Only fixed SQL fragments are concatenated; every value is a bound parameter.
        String where = "";
        if (subject != null) where += " AND subject_type = :subject";
        if (id != null) where += " AND subject_id = :sid";
        var query = jdbc.sql("SELECT id, at, actor, action, subject_type, subject_id, from_status, to_status, detail"
                        + " FROM audit_events WHERE true" + where + " ORDER BY id DESC LIMIT :n")
                .param("n", Math.max(1, Math.min(limit, 200)));
        if (subject != null) query = query.param("subject", subject.toUpperCase());
        if (id != null) query = query.param("sid", id);
        List<Map<String, Object>> rows = query.query().listOfRows();
        return ResponseEntity.ok(rows);
    }
}