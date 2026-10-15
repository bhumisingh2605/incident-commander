package com.bhumi.commander.agent;

import com.bhumi.commander.remediation.ErrorRateProbe;
import com.bhumi.commander.remediation.LatencyProbe;
import com.bhumi.commander.tools.ServiceRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Deterministic check: is an error-rate or latency alert still reproducing? If not, no model call is needed. */
@Component
public class AlertConfirmation {

    private static final Logger log = LoggerFactory.getLogger(AlertConfirmation.class);
    private static final double ERROR_THRESHOLD = 0.05;
    // Must match the HighLatencyP95 rule in infra/prometheus/alert-rules.yml (1 second).
    private static final double LATENCY_THRESHOLD_SECONDS = 1.0;

    private final ErrorRateProbe errorProbe;
    private final LatencyProbe latencyProbe;
    private final ServiceRegistry services;

    public AlertConfirmation(ErrorRateProbe errorProbe, LatencyProbe latencyProbe, ServiceRegistry services) {
        this.errorProbe = errorProbe;
        this.latencyProbe = latencyProbe;
        this.services = services;
    }

    /** Returns a finished result when the alert is not reproducing; otherwise null. */
    public AnalystAgent.Result checkEarly(String runId, String alertText, long startNanos) {
        String lower = alertText.toLowerCase();
        boolean errorAlert = lower.contains("error rate") || lower.contains("higherrorrate");
        boolean latencyAlert = !errorAlert && (lower.contains("latency") || lower.contains("highlatency"));
        if (!errorAlert && !latencyAlert) return null;

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
            try {
                if (errorAlert) {
                    OptionalDouble rate = errorProbe.errorRate(s);
                    if (rate.isEmpty() || rate.getAsDouble() > ERROR_THRESHOLD) return null;
                    evidence.add("%s error rate is %.1f%% over the last minute, below the 5%% alert threshold"
                            .formatted(s, rate.getAsDouble() * 100));
                } else {
                    OptionalDouble p95 = latencyProbe.p95Seconds(s);
                    if (p95.isEmpty() || p95.getAsDouble() > LATENCY_THRESHOLD_SECONDS) return null;
                    evidence.add("%s p95 latency is %.0f ms over the last 5 minutes, below the 1000 ms alert threshold"
                            .formatted(s, p95.getAsDouble() * 1000));
                }
            } catch (Exception e) {
                return null;
            }
        }

        String summary = "Alert not confirmed: " + (errorAlert ? "error rates are" : "p95 latency is")
                + " below the alert threshold for " + String.join(", ", targets)
                + ". The fault may have cleared before the investigation.";
        RootCause rc = new RootCause(summary, "unknown", "UNKNOWN", evidence, 0.2, List.of());
        long seconds = (System.nanoTime() - startNanos) / 1_000_000_000L;
        log.info("Alert not confirmed for run {}: {}", runId, evidence);
        return new AnalystAgent.Result(runId, rc, String.join("\n", evidence), 0, seconds);
    }
}