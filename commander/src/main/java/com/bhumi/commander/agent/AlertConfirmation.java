package com.bhumi.commander.agent;

import com.bhumi.commander.remediation.ErrorRateProbe;
import com.bhumi.commander.tools.ServiceRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Deterministic check: is an error-rate alert still reproducing? If not, no model call is needed. */
@Component
public class AlertConfirmation {

    private static final Logger log = LoggerFactory.getLogger(AlertConfirmation.class);
    private static final double THRESHOLD = 0.05;

    private final ErrorRateProbe probe;
    private final ServiceRegistry services;

    public AlertConfirmation(ErrorRateProbe probe, ServiceRegistry services) {
        this.probe = probe;
        this.services = services;
    }

    /** Returns a finished result when an error-rate alert is not reproducing; otherwise null. */
    public AnalystAgent.Result checkEarly(String runId, String alertText, long startNanos) {
        String lower = alertText.toLowerCase();
        if (!lower.contains("error rate") && !lower.contains("higherrorrate")) return null;

        String alerting = services.names().stream()
                .filter(alertText::contains)
                .min(Comparator.comparingInt(alertText::indexOf))
                .orElse(null);
        if (alerting == null) return null;

        List<String> targets = new ArrayList<>();
        targets.add(alerting);
        targets.addAll(services.downstreamOf(alerting));

        List<String> evidence = new ArrayList<>();
        for (String s : targets) {
            OptionalDouble rate;
            try {
                rate = probe.errorRate(s);
            } catch (Exception e) {
                return null;
            }
            // No traffic, or a failing service: cannot rule the alert out, so investigate normally.
            if (rate.isEmpty() || rate.getAsDouble() > THRESHOLD) return null;
            evidence.add("%s error rate is %.1f%% over the last minute, below the 5%% alert threshold"
                    .formatted(s, rate.getAsDouble() * 100));
        }

        String summary = "Alert not confirmed: error rates are below the alert threshold for "
                + String.join(", ", targets) + ". The fault may have cleared before the investigation.";
        RootCause rc = new RootCause(summary, "unknown", "UNKNOWN", evidence, 0.2, List.of());
        long seconds = (System.nanoTime() - startNanos) / 1_000_000_000L;
        log.info("Alert not confirmed for run {}: {}", runId, evidence);
        return new AnalystAgent.Result(runId, rc, String.join("\n", evidence), 0, seconds);
    }
}
