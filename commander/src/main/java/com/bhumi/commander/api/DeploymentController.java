package com.bhumi.commander.api;

import com.bhumi.commander.tools.ServiceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/deployments")
public class DeploymentController {

    public record DeployRequest(String service, String version, String deployedBy, String notes) {}

    private final JdbcClient jdbc;
    private final ServiceRegistry services;

    public DeploymentController(JdbcClient jdbc, ServiceRegistry services) {
        this.jdbc = jdbc;
        this.services = services;
    }

    @PostMapping
    public ResponseEntity<String> record(@RequestBody DeployRequest req) {
        if (!services.known(req.service())) {
            return ResponseEntity.badRequest().body("Unknown service. Known: " + services.names());
        }
        if (req.version() == null || req.version().isBlank() || req.version().length() > 50) {
            return ResponseEntity.badRequest().body("version is required (max 50 characters)");
        }
        String by = (req.deployedBy() == null || req.deployedBy().isBlank()) ? "unknown" : req.deployedBy();
        jdbc.sql("INSERT INTO deployments (service, version, deployed_by, notes) VALUES (:s, :v, :b, :n)")
                .param("s", req.service())
                .param("v", req.version())
                .param("b", by)
                .param("n", req.notes())
                .update();
        return ResponseEntity.ok("recorded " + req.service() + " " + req.version());
    }
}