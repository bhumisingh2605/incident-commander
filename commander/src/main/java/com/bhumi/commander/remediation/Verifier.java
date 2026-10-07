package com.bhumi.commander.remediation;

import com.bhumi.commander.tools.HealthTools;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class Verifier implements DisposableBean {

    private record Check(boolean passed, String note) {}

    private static final Logger log = LoggerFactory.getLogger(Verifier.class);
    private static final double MAX_ERROR_RATE = 0.05;
    private static final int[] DELAY_SECONDS = {90, 45, 45};

    private final JdbcClient jdbc;
    private final HealthTools health;
    private final ErrorRateProbe probe;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "verifier");
        t.setDaemon(true);
        return t;
    });

    public Verifier(JdbcClient jdbc, HealthTools health, ErrorRateProbe probe) {
        this.jdbc = jdbc;
        this.health = health;
        this.probe = probe;
    }

    @Override
    public void destroy() { scheduler.shutdownNow(); }

    /** Called right after an approved action ran. */
    public void scheduleVerification(long incidentId, String targetService) {
        schedule(incidentId, targetService, 0);
    }

    private void schedule(long incidentId, String target, int attempt) {
        scheduler.schedule(() -> run(incidentId, target, attempt), DELAY_SECONDS[attempt], TimeUnit.SECONDS);
    }

    private void run(long incidentId, String target, int attempt) {
        try {
            Check c = check(incidentId, target);
            if (c.passed()) {
                finish(incidentId, true, "Verified after attempt " + (attempt + 1) + ": " + c.note());
                return;
            }
            if (attempt + 1 < DELAY_SECONDS.length) {
                log.info("Verification attempt {} failed for incident {}: {}; retrying",
                        attempt + 1, incidentId, c.note());
                schedule(incidentId, target, attempt + 1);
                return;
            }
            finish(incidentId, false, "Verification failed: " + c.note());
        } catch (Exception e) {
            log.error("Verification error for incident {}: {}", incidentId, e.toString());
            finish(incidentId, false, "Verification error: " + e);
        }
    }

    private Check check(long incidentId, String target) {
        Set<String> toCheck = new LinkedHashSet<>();
        toCheck.add(target);
        List<String> incidentService = jdbc.sql("SELECT service FROM incidents WHERE id = :i")
                .param("i", incidentId).query(String.class).list();
        if (!incidentService.isEmpty() && incidentService.get(0) != null) {
            toCheck.add(incidentService.get(0));
        }

        StringBuilder notes = new StringBuilder();
        boolean sawTraffic = false;
        for (String s : toCheck) {
            String h = health.getServiceHealth(s);
            if (!h.contains("health: UP")) return new Check(false, h);

            OptionalDouble rate = probe.errorRate(s);
            if (rate.isPresent()) {
                sawTraffic = true;
                double pct = rate.getAsDouble() * 100;
                if (rate.getAsDouble() > MAX_ERROR_RATE) {
                    return new Check(false, "%s error rate is %.1f%% (limit %.0f%%)"
                            .formatted(s, pct, MAX_ERROR_RATE * 100));
                }
                notes.append("%s up, error rate %.1f%%; ".formatted(s, pct));
            } else {
                notes.append(s).append(" up, no recent traffic; ");
            }
        }
        if (!sawTraffic) notes.append("health checks only, no traffic to measure");
        return new Check(true, notes.toString().trim());
    }

    private void finish(long incidentId, boolean ok, String note) {
        String text = note.length() > 1000 ? note.substring(0, 1000) : note;
        jdbc.sql("UPDATE incidents SET verification = :v, verified_at = now(), updated_at = now() WHERE id = :i")
                .param("v", text).param("i", incidentId).update();

        // Status only changes while the incident is still open (a human or the alerts may have closed it).
        if (ok) {
            jdbc.sql("UPDATE incidents SET status = 'RESOLVED', resolved_at = now() WHERE id = :i AND resolved_at IS NULL")
                    .param("i", incidentId).update();
        } else {
            jdbc.sql("UPDATE incidents SET status = 'ESCALATED' WHERE id = :i AND resolved_at IS NULL")
                    .param("i", incidentId).update();
        }
        log.info("Incident {} verification {}: {}", incidentId, ok ? "PASSED" : "FAILED", text);
    }
}