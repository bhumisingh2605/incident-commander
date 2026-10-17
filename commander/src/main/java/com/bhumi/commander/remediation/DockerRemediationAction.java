
package com.bhumi.commander.remediation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class DockerRemediationAction implements RemediationAction {

    private final JdbcClient jdbc;

    public DockerRemediationAction(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String restart(String container)
            throws IOException, InterruptedException {

        Process p = new ProcessBuilder(
                "docker", "restart", container)
                .redirectErrorStream(true)
                .start();

        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("docker restart timed out");
        }

        String out = new String(
                p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();

        if (p.exitValue() != 0) {
            throw new IOException("docker restart failed: " + out);
        }

        return "restarted " + container;
    }

    @Override
    public String rollback(String service)
            throws IOException, InterruptedException {

        List<String> versions = jdbc.sql("""
                SELECT version
                FROM deployments
                WHERE service = :s
                ORDER BY deployed_at DESC
                LIMIT 2
                """)
                .param("s", service)
                .query(String.class)
                .list();

        if (versions.size() < 2) {
            throw new IOException(
                    "no previous version recorded for "
                            + service + "; cannot roll back");
        }

        String restarted = restart(service);

        jdbc.sql("""
                INSERT INTO deployments
                    (service, version, deployed_by, notes)
                VALUES
                    (:s, :v, 'commander', :n)
                """)
                .param("s", service)
                .param("v", versions.get(1))
                .param("n", "rollback from "
                        + versions.get(0) + " (simulated)")
                .update();

        return "rolled back " + service + " to " + versions.get(1)
                + " (simulated; " + restarted + ")";
    }
}
