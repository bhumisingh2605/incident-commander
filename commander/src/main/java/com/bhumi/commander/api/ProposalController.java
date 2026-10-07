package com.bhumi.commander.api;

import com.bhumi.commander.remediation.ActionExecutor;
import com.bhumi.commander.remediation.ProposalService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proposals")
public class ProposalController {

    private static final Logger log = LoggerFactory.getLogger(ProposalController.class);

    private final JdbcClient jdbc;
    private final ProposalService proposals;
    private final ActionExecutor executor;

    public ProposalController(JdbcClient jdbc, ProposalService proposals, ActionExecutor executor) {
        this.jdbc = jdbc;
        this.proposals = proposals;
        this.executor = executor;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) {
            return jdbc.sql("SELECT * FROM action_proposals ORDER BY id DESC LIMIT 50").query().listOfRows();
        }
        return jdbc.sql("SELECT * FROM action_proposals WHERE status = :s ORDER BY id DESC LIMIT 50")
                .param("s", status.toUpperCase()).query().listOfRows();
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<String> approve(@PathVariable long id, @RequestParam String by) {
        ResponseEntity<String> decided = decide(id, true, by);
        if (!decided.getStatusCode().is2xxSuccessful()) return decided;
        ActionExecutor.Result r = executor.execute(id);
        log.info("Proposal {} approved by {}; execution {}: {}", id, by, r.outcome(), r.message());
        return ResponseEntity.ok("approved " + id + "; execution: " + r.outcome() + " - " + r.message());
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<String> reject(@PathVariable long id, @RequestParam String by) {
        return decide(id, false, by);
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<String> execute(@PathVariable long id, @RequestParam String by) {
        if (by == null || by.isBlank() || by.length() > 100) {
            return ResponseEntity.badRequest().body("'by' is required (max 100 characters)");
        }
        ActionExecutor.Result r = executor.execute(id);
        log.info("Execution of proposal {} requested by {}: {} - {}", id, by, r.outcome(), r.message());
        int status = switch (r.outcome()) {
            case EXECUTED -> 200;
            case NOT_FOUND -> 404;
            case COOLDOWN, NOT_APPROVED -> 409;
            case FAILED -> 500;
        };
        return ResponseEntity.status(status).body(r.outcome() + " - " + r.message());
    }

    private ResponseEntity<String> decide(long id, boolean approve, String by) {
        if (by == null || by.isBlank() || by.length() > 100) {
            return ResponseEntity.badRequest().body("'by' is required (max 100 characters)");
        }
        return switch (proposals.decide(id, approve, by.trim())) {
            case OK -> ResponseEntity.ok((approve ? "approved " : "rejected ") + id);
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case ALREADY_DECIDED -> ResponseEntity.status(409).body("proposal " + id + " was already decided");
        };
    }
}