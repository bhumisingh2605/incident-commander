package com.bhumi.commander.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final JdbcClient jdbc;

    public AuditController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/tool-calls")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "20") int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        return jdbc.sql("""
                SELECT id, run_id, tool_name, args, status, duration_ms, created_at,
                       left(result_summary, 200) AS result
                FROM tool_calls ORDER BY id DESC LIMIT :n
                """)
                .param("n", n)
                .query()
                .listOfRows();
    }
}