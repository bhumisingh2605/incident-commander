
        package com.bhumi.commander.agent;

import com.bhumi.commander.agent.RootCause;
import com.bhumi.commander.tools.ServiceRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RootCauseGuard {

    private static final Logger log =
            LoggerFactory.getLogger(RootCauseGuard.class);

    private static final int RECENT_DEPLOY_MINUTES = 30;

    private static final double HIGH_LATENCY_THRESHOLD_MS = 1000.0;
    private static final double HEALTHY_LATENCY_THRESHOLD_MS = 1000.0;
    private static final double LOW_ERROR_RATE_THRESHOLD_PERCENT = 0.1;

    private static final Pattern CURRENT_METRIC_PATTERN =
            Pattern.compile(
                    "\\bcurrent\\s+([0-9]+(?:\\.[0-9]+)?)",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Pattern ERROR_RATE_PATTERN =
            Pattern.compile(
                    "\\bcurrent\\s+([0-9]+(?:\\.[0-9]+)?)\\s*%",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Pattern LATENCY_P95_PATTERN =
            Pattern.compile(
                    "latency_p95\\s*:\\s*(?:current\\s*)?"
                            + "([0-9]+(?:\\.[0-9]+)?)\\s*(?:ms)?",
                    Pattern.CASE_INSENSITIVE
            );

    private final JdbcClient jdbc;
    private final ServiceRegistry services;

    public RootCauseGuard(JdbcClient jdbc, ServiceRegistry services) {
        this.jdbc = jdbc;
        this.services = services;
    }

    public RootCause apply(
            RootCause rc,
            String alertText,
            String evidenceText) {

        if (rc == null) {
            return new RootCause(
                    "No diagnosis was produced",
                    "unknown",
                    "UNKNOWN",
                    List.of("Guardrail: model returned null diagnosis"),
                    0.0,
                    List.of()
            );
        }

        String svc = rc.suspectedService();

        String evidence = evidenceText == null
                ? ""
                : evidenceText.toLowerCase(Locale.ROOT);

        // RULE 1: FALSE ALARM
        if (isFalseAlarm(evidence, alertText)) {
            List<String> updatedEvidence =
                    new ArrayList<>(rc.evidence());

            updatedEvidence.add(
                    "Guardrail: available telemetry does not support "
                            + "an active failure; classified as UNKNOWN"
            );

            log.info(
                    "Guardrail changed {} -> UNKNOWN because telemetry "
                            + "does not support the alert",
                    svc
            );

            return new RootCause(
                    "Alert is not supported by current telemetry; "
                            + "no active failure confirmed.",
                    "unknown",
                    "UNKNOWN",
                    updatedEvidence,
                    Math.min(rc.confidence(), 0.3),
                    List.of()
            );
        }

        // Nothing more to validate without a usable service.
        if (svc == null || svc.equalsIgnoreCase("unknown")) {
            return rc;
        }

        if (!services.known(svc)) {
            return rc;
        }

        String category = rc.category();

        // RULE 2: CORRECT A FALSE DOWNSTREAM LATENCY DIAGNOSIS
        if (!isFailedUpstreamDependency(svc, evidence)) {
            for (String upstream : services.upstreamOf(svc)) {
                if (hasHighRisingLatency(upstream, evidence)
                        && hasHealthyMetrics(svc, evidence)) {

                    List<String> updatedEvidence =
                            new ArrayList<>(rc.evidence());

                    updatedEvidence.add(
                            "Guardrail: corrected suspected service from "
                                    + svc + " to " + upstream
                                    + " because " + upstream
                                    + " has high, rising latency while "
                                    + svc
                                    + " has healthy, stable metrics"
                    );

                    log.info(
                            "Guardrail corrected suspected service {} -> {} "
                                    + "based on latency metrics",
                            svc,
                            upstream
                    );

                    return new RootCause(
                            rc.summary(),
                            upstream,
                            "SLOW_DEPENDENCY",
                            updatedEvidence,
                            Math.max(rc.confidence(), 0.8),
                            rc.suggestedActions()
                    );
                }
            }
        }

        // RULE 3: DOWNSTREAM DEPENDENCY FAILURE
        for (String downstream : services.downstreamOf(svc)) {
            if (downstreamFailureIsVisible(downstream, evidence)
                    && upstreamFailureIsVisible(svc, evidence)) {

                List<String> updatedEvidence =
                        new ArrayList<>(rc.evidence());

                updatedEvidence.add(
                        "Guardrail: " + downstream
                                + " is unhealthy/down while " + svc
                                + " shows connection/I/O failures; "
                                + downstream
                                + " is the root-cause service"
                );

                log.info(
                        "Guardrail corrected root cause {} -> {}",
                        svc,
                        downstream
                );

                return new RootCause(
                        rc.summary(),
                        downstream,
                        "DEPENDENCY_FAILURE",
                        updatedEvidence,
                        Math.max(rc.confidence(), 0.9),
                        rc.suggestedActions()
                );
            }
        }

        // RULE 4: SERVICE-DOWN DEPENDENCY
        if ("DEPENDENCY_FAILURE".equals(category)
                && isFailedUpstreamDependency(svc, evidence)) {

            List<String> updatedEvidence =
                    new ArrayList<>(rc.evidence());

            updatedEvidence.add(
                    "Guardrail: " + svc
                            + " is the dependency reported as failed "
                            + "by an upstream service; classified as "
                            + "SERVICE_DOWN"
            );

            log.info(
                    "Guardrail corrected dependency failure "
                            + "{} -> SERVICE_DOWN",
                    svc
            );

            return new RootCause(
                    rc.summary(),
                    svc,
                    "SERVICE_DOWN",
                    updatedEvidence,
                    Math.max(rc.confidence(), 0.9),
                    rc.suggestedActions()
            );
        }

        // RULE 5: RECENT DEPLOYMENT
        String corrected = category;
        String reason = null;

        if (hasRecentDeploy(svc)
                && ("APPLICATION_ERROR".equals(category)
                || "DEPENDENCY_FAILURE".equals(category))) {

            corrected = "BAD_DEPLOY";
            reason = svc + " was deployed in the last "
                    + RECENT_DEPLOY_MINUTES + " minutes";

            // RULE 6: SERVICE WITH NO DOWNSTREAM
        } else if ("DEPENDENCY_FAILURE".equals(category)
                && services.downstreamOf(svc).isEmpty()) {

            corrected = alertText != null
                    && alertText.toLowerCase(Locale.ROOT).contains("latency")
                    ? "SLOW_DEPENDENCY"
                    : "APPLICATION_ERROR";

            reason = svc
                    + " calls no other service, so it "
                    + "cannot fail because of a dependency";
        }

        if (corrected.equals(category)) {
            return rc;
        }

        List<String> updatedEvidence =
                new ArrayList<>(rc.evidence());

        updatedEvidence.add(
                "Guardrail: category changed from "
                        + category + " to " + corrected
                        + " (" + reason + ")"
        );

        log.info(
                "Guardrail changed category {} -> {} for {}: {}",
                category,
                corrected,
                svc,
                reason
        );

        return new RootCause(
                rc.summary(),
                svc,
                corrected,
                updatedEvidence,
                rc.confidence(),
                rc.suggestedActions()
        );
    }

    /**
     * Detect high, rising p95 latency for a service.
     */
    private boolean hasHighRisingLatency(
            String service,
            String evidence) {

        for (String line : evidence.split("\\R")) {
            if (!line.contains(service.toLowerCase(Locale.ROOT))
                    || !line.contains("latency_p95")
                    || !line.contains("trend: rising")) {
                continue;
            }

            Matcher matcher = CURRENT_METRIC_PATTERN.matcher(line);

            if (matcher.find()) {
                double latency = Double.parseDouble(matcher.group(1));

                if (latency >= HIGH_LATENCY_THRESHOLD_MS) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check for stable, low latency and a low error rate.
     */
    private boolean hasHealthyMetrics(
            String service,
            String evidence) {

        boolean stableLatency = false;
        boolean lowErrors = false;

        for (String line : evidence.split("\\R")) {
            if (!line.contains(service.toLowerCase(Locale.ROOT))) {
                continue;
            }

            if (line.contains("latency_p95")
                    && line.contains("trend: stable")) {

                Matcher matcher = CURRENT_METRIC_PATTERN.matcher(line);

                if (matcher.find()) {
                    double latency = Double.parseDouble(matcher.group(1));
                    stableLatency =
                            latency <= HEALTHY_LATENCY_THRESHOLD_MS;
                }
            }

            if (line.contains("error_rate")) {
                Matcher matcher = ERROR_RATE_PATTERN.matcher(line);

                if (matcher.find()) {
                    double errorRate =
                            Double.parseDouble(matcher.group(1));

                    lowErrors =
                            errorRate <= LOW_ERROR_RATE_THRESHOLD_PERCENT;
                }
            }
        }

        return stableLatency && lowErrors;
    }

    /**
     * Detect whether the suspected service is itself a failed dependency.
     */
    private boolean isFailedUpstreamDependency(
            String service,
            String evidence) {

        String target = service.toLowerCase(Locale.ROOT).trim();

        boolean callerPointsToService =
                evidence.contains("errors pointing to " + target)
                        || evidence.contains("error pointing to " + target)
                        || evidence.contains("failed calls to " + target)
                        || evidence.contains("failed call to " + target)
                        || evidence.contains("calls to " + target)
                        || evidence.contains("calling " + target);

        boolean serviceLooksDown =
                evidence.contains("unreachable")
                        || evidence.contains("connection refused")
                        || evidence.contains("service down")
                        || evidence.contains("service is down")
                        || evidence.contains("not running")
                        || evidence.contains("status=down")
                        || evidence.contains("health=down")
                        || evidence.contains("no response");

        boolean hasUpstreamCaller =
                !services.upstreamOf(target).isEmpty();

        if (hasUpstreamCaller
                && (callerPointsToService || serviceLooksDown)) {
            return true;
        }

        return callerPointsToService;
    }

    /**
     * Detect explicit non-latency failure evidence.
     * Metric names alone do not prove a failure.
     */
    private boolean hasExplicitOtherFailureEvidence(String evidence) {

        boolean activeHeapFailure =
                evidence.contains("highheapusage")
                        || evidence.contains("high heap")
                        || evidence.contains("heap above")
                        || evidence.contains("heap reached")
                        || evidence.contains("heap is rising")
                        || evidence.contains("heap rising")
                        || evidence.contains("outofmemory");

        boolean activeCpuFailure =
                evidence.contains("high cpu")
                        || evidence.contains("cpu above")
                        || evidence.contains("cpu exhausted")
                        || evidence.contains("cpu exhaustion");

        boolean activeServiceFailure =
                evidence.contains("service down")
                        || evidence.contains("service is down")
                        || evidence.contains("unhealthy")
                        || evidence.contains("unreachable")
                        || evidence.contains("connection refused")
                        || evidence.contains("failed to connect")
                        || evidence.contains("unable to connect")
                        || evidence.contains("resourceaccessexception")
                        || evidence.contains("not running")
                        || evidence.contains("health=down")
                        || evidence.contains("status=down");

        return activeHeapFailure
                || activeCpuFailure
                || activeServiceFailure
                || hasHighErrorRate(evidence);
    }

    /**
     * Detect a measured error rate above the configured healthy limit.
     */
    private boolean hasHighErrorRate(String evidence) {

        for (String line : evidence.split("\\R")) {
            if (!line.contains("error_rate")
                    && !line.contains("error rate")) {
                continue;
            }

            Matcher matcher = ERROR_RATE_PATTERN.matcher(line);

            if (matcher.find()) {
                double errorRate = Double.parseDouble(matcher.group(1));

                if (errorRate > LOW_ERROR_RATE_THRESHOLD_PERCENT) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Return true only when latency measurements exist and every
     * reported p95 value is below the configured alert threshold.
     */
    private boolean hasOnlyHealthyLatencyMetrics(String evidence) {

        boolean foundLatencyMetric = false;

        for (String line : evidence.split("\\R")) {
            if (!line.contains("latency_p95")) {
                continue;
            }

            Matcher matcher = LATENCY_P95_PATTERN.matcher(line);

            if (!matcher.find()) {
                // A reported metric we cannot parse is not proof of health.
                return false;
            }

            double latency = Double.parseDouble(matcher.group(1));
            foundLatencyMetric = true;

            if (latency >= HEALTHY_LATENCY_THRESHOLD_MS) {
                return false;
            }
        }

        return foundLatencyMetric;
    }

    /**
     * Detect a false alarm.
     * Missing data alone does not prove an incident is false.
     */
    private boolean isFalseAlarm(
            String evidence,
            String alertText) {

        String normalizedEvidence = evidence == null
                ? ""
                : evidence.toLowerCase(Locale.ROOT);

        String alert = alertText == null
                ? ""
                : alertText.toLowerCase(Locale.ROOT);

        // RULE A: LATENCY ALERT WITH HEALTHY MEASUREMENTS
        boolean latencyAlert = alert.contains("latency");

        boolean otherFailure =
                hasExplicitOtherFailureEvidence(normalizedEvidence);

        boolean healthyLatency =
                hasOnlyHealthyLatencyMetrics(normalizedEvidence);

        log.info(
                "False-alarm diagnostic: latencyAlert={}, "
                        + "otherFailure={}, healthyLatency={}, alert={}",
                latencyAlert,
                otherFailure,
                healthyLatency,
                alert
        );

        if (latencyAlert && !otherFailure && healthyLatency) {
            log.info(
                    "Latency alert classified as unsupported: "
                            + "reported p95 metrics are below {} ms",
                    HEALTHY_LATENCY_THRESHOLD_MS
            );

            return true;
        }

        boolean activeHeapFailure =
                normalizedEvidence.contains("highheapusage")
                        || normalizedEvidence.contains("high heap")
                        || normalizedEvidence.contains("heap above")
                        || normalizedEvidence.contains("heap reached")
                        || normalizedEvidence.contains("heap is rising")
                        || normalizedEvidence.contains("heap rising")
                        || normalizedEvidence.contains("outofmemory");

        boolean activeCpuFailure =
                normalizedEvidence.contains("high cpu")
                        || normalizedEvidence.contains("cpu above")
                        || normalizedEvidence.contains("cpu exhausted")
                        || normalizedEvidence.contains("cpu exhaustion");

        boolean activeErrorFailure =
                hasHighErrorRate(normalizedEvidence)
                        || (normalizedEvidence.contains("error rate")
                        && !normalizedEvidence.contains("no error rate"));

        boolean activeServiceFailure =
                normalizedEvidence.contains("service down")
                        || normalizedEvidence.contains("service is down")
                        || normalizedEvidence.contains("unhealthy")
                        || normalizedEvidence.contains("unreachable")
                        || normalizedEvidence.contains("connection refused")
                        || normalizedEvidence.contains("failed to connect")
                        || normalizedEvidence.contains("unable to connect")
                        || normalizedEvidence.contains("resourceaccessexception");

        boolean activeAlert =
                normalizedEvidence.contains("alert fired")
                        || normalizedEvidence.contains("alert firing")
                        || normalizedEvidence.contains("firing for");

        boolean activeFailure =
                activeHeapFailure
                        || activeCpuFailure
                        || activeErrorFailure
                        || activeServiceFailure
                        || activeAlert;

        boolean alertIndicatesActiveFailure =
                alert.contains("firing")
                        || alert.contains("highheapusage")
                        || alert.contains("higherrorrate")
                        || alert.contains("highcpu")
                        || alert.contains("memory")
                        || alert.contains("heap")
                        || alert.contains("cpu");

        if (activeFailure || alertIndicatesActiveFailure) {
            return false;
        }

        boolean noErrors =
                normalizedEvidence.contains("no error")
                        || normalizedEvidence.contains("no errors")
                        || normalizedEvidence.contains("no warn")
                        || normalizedEvidence.contains("no warning")
                        || normalizedEvidence.contains("no logs")
                        || normalizedEvidence.contains("no error/warn")
                        || normalizedEvidence.contains("no error or warn");

        boolean noData = normalizedEvidence.contains("no data");

        boolean falling =
                normalizedEvidence.contains("falling")
                        || normalizedEvidence.contains("decreasing")
                        || normalizedEvidence.contains("declining");

        boolean healthy =
                normalizedEvidence.contains("health=up")
                        || normalizedEvidence.contains("status=up")
                        || normalizedEvidence.contains("healthy")
                        || normalizedEvidence.contains("\"status\":\"up\"")
                        || normalizedEvidence.contains("marked as up");

        // Existing S8 false-alarm rule.
        if (noErrors && noData) {
            return true;
        }

        return noErrors && falling && healthy;
    }

    /**
     * Check whether a downstream service appears unavailable.
     */
    private boolean downstreamFailureIsVisible(
            String downstream,
            String evidence) {

        String service = downstream.toLowerCase(Locale.ROOT);

        boolean mentionsService = evidence.contains(service);

        boolean down =
                evidence.contains("down")
                        || evidence.contains("unhealthy")
                        || evidence.contains("unreachable")
                        || evidence.contains("no response")
                        || evidence.contains("status=down")
                        || evidence.contains("health=down")
                        || evidence.contains("connection refused");

        return mentionsService && down;
    }

    /**
     * Check whether an upstream service has connection/I/O failures.
     */
    private boolean upstreamFailureIsVisible(
            String upstream,
            String evidence) {

        String service = upstream.toLowerCase(Locale.ROOT);

        boolean mentionsService = evidence.contains(service);

        boolean dependencyError =
                evidence.contains("resourceaccess")
                        || evidence.contains("connection refused")
                        || evidence.contains("connection reset")
                        || evidence.contains("i/o error")
                        || evidence.contains("io error")
                        || evidence.contains("timeout")
                        || evidence.contains("failed to connect")
                        || evidence.contains("unable to connect");

        return mentionsService && dependencyError;
    }

    /**
     * Check whether the service has a recent deployment.
     */
    private boolean hasRecentDeploy(String service) {

        Long count = jdbc.sql(
                        "SELECT count(*) "
                                + "FROM deployments "
                                + "WHERE service = :s "
                                + "AND deployed_at > :since"
                )
                .param("s", service)
                .param(
                        "since",
                        OffsetDateTime.now()
                                .minusMinutes(RECENT_DEPLOY_MINUTES)
                )
                .query(Long.class)
                .single();

        return count != null && count > 0;
    }
}
