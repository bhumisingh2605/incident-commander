package com.bhumi.commander.agent;

import com.bhumi.commander.tools.HealthTools;
import com.bhumi.commander.tools.ServiceRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Deterministic check: a service that does not answer its health check is down. */
@Component
public class ServiceDownCheck {

    private static final Logger log = LoggerFactory.getLogger(ServiceDownCheck.class);

    private final HealthTools health;
    private final ServiceRegistry services;

    public ServiceDownCheck(HealthTools health, ServiceRegistry services) {
        this.health = health;
        this.services = services;
    }

    /** Returns a finished result when a relevant service is down; otherwise null. */
    public AnalystAgent.Result checkEarly(String runId, String alertText, long startNanos) {
        String alerting = services.names().stream()
                .filter(alertText::contains)
                .min(Comparator.comparingInt(alertText::indexOf))
                .orElse(null);

        List<String> targets = new ArrayList<>();
        if (alerting == null) {
            targets.addAll(services.names());
        } else {
            targets.addAll(services.downstreamOf(alerting));
            targets.add(alerting);
        }

        for (String s : targets) {
            String h = health.getServiceHealth(s);
            if (h.contains("health: UP")) continue;
            if (!h.contains("DOWN") && !h.contains("UNREACHABLE")) continue;

            List<String> evidence = List.of(h, "Downstream services are checked before the alerting service, "
                    + "so the down service is the origin of the failure.");
            String summary = s + " is not answering its health check, so it is down.";
            RootCause rc = new RootCause(summary, s, "SERVICE_DOWN", evidence, 0.9,
                    List.of("RESTART_SERVICE " + s));
            long seconds = (System.nanoTime() - startNanos) / 1_000_000_000L;
            log.info("Service down detected for run {}: {}", runId, h);
            return new AnalystAgent.Result(runId, rc, h, 0, seconds);
        }
        return null;
    }
}