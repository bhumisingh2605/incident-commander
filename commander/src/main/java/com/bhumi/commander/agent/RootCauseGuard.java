
package com.bhumi.commander.agent;

import com.bhumi.commander.tools.ServiceRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private static final double HEALTHY_LATENCY_THRESHOLD_MS = 100.0;
    private static final double LOW_ERROR_RATE_THRESHOLD_PERCENT = 0.1;

    private static final Pattern CURRENT_METRIC_PATTERN =
            Pattern.compile("current\\s+([0-9]+(?:\\.[0-9]+)?)");

    private static final Pattern ERROR_RATE_PATTERN =
            Pattern.compile("current\\s+([0-9]+(?:\\.[0-9]+)?)\\s*%");

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

        // RULE 0: NULL MODEL RESPONSE
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
                : evidenceText.toLowerCase();

        // RULE 1: FALSE ALARM
        if (isFalseAlarm(evidence, alertText)) {
            List<String> updatedEvidence =
                    new ArrayList<>(rc.evidence());

            updatedEvidence.add(
                    "Guardrail: no active failure evidence detected; "
                            + "classified as UNKNOWN"
            );

            log.info(
                    "Guardrail changed {} -> UNKNOWN because evidence "
                            + "indicates no active incident",
                    svc
            );

            return new RootCause(
                    rc.summary(),
                    "unknown",
                    "UNKNOWN",
                    updatedEvidence,
                    Math.min(rc.confidence(), 0.3),
                    rc.suggestedActions()
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

        /*
         * RULE 2: CORRECT A FALSE DOWNSTREAM LATENCY DIAGNOSIS
         *
         * Example:
         * order-service -> payment-service
         *
         * If the model blames payment-service while order-service
         * has high, rising latency and payment-service is healthy,
         * prefer order-service as the suspected root cause.
         *
         * Do not override direct evidence of a failed dependency.
         * This protects the S5 case.
         */

        

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
                                    + svc + " has healthy, stable metrics"
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

        /*
         * RULE 3: DOWNSTREAM DEPENDENCY FAILURE
         *
         * If the suspected service is failing because a downstream
         * dependency is unhealthy, identify that dependency.
         */
        for (String downstream : services.downstreamOf(svc)) {

            if (downstreamFailureIsVisible(downstream, evidence)
                    && upstreamFailureIsVisible(svc, evidence)) {

                List<String> updatedEvidence =
                        new ArrayList<>(rc.evidence());

                updatedEvidence.add(
                        "Guardrail: " + downstream
                                + " is unhealthy/down while " + svc
                                + " shows connection/I/O failures; "
                                + downstream + " is the root-cause service"
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

        /*
         * RULE 4: SERVICE-DOWN DEPENDENCY
         *
         * Protects the S5 case where payment-service is down and
         * order-service reports failures pointing to payment-service.
         */
        if (category.equals("DEPENDENCY_FAILURE")
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

        /*
         * RULE 5: RECENT DEPLOYMENT
         */
        String corrected = category;
        String reason = null;

        if (hasRecentDeploy(svc)
                && (category.equals("APPLICATION_ERROR")
                || category.equals("DEPENDENCY_FAILURE"))) {

            corrected = "BAD_DEPLOY";

            reason = svc + " was deployed in the last "
                    + RECENT_DEPLOY_MINUTES + " minutes";
        }

        /*
         * RULE 6: SERVICE WITH NO DOWNSTREAM
         *
         * A leaf service cannot have a failure caused by its own
         * downstream dependency. Rule 4 runs first for S5.
         */
        else if (category.equals("DEPENDENCY_FAILURE")
                && services.downstreamOf(svc).isEmpty()) {

            corrected = alertText != null
                    && alertText.toLowerCase().contains("latency")
                    ? "SLOW_DEPENDENCY"
                    : "APPLICATION_ERROR";

            reason = svc
                    + " calls no other service, so it "
                    + "cannot fail because of a dependency";
        }

        // NO CHANGE REQUIRED
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

            if (!line.contains(service.toLowerCase())
                    || !line.contains("latency_p95")
                    || !line.contains("trend: rising")) {
                continue;
            }

            Matcher matcher =
                    CURRENT_METRIC_PATTERN.matcher(line);

            if (matcher.find()) {
                double latency =
                        Double.parseDouble(matcher.group(1));

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

            if (!line.contains(service.toLowerCase())) {
                continue;
            }

            if (line.contains("latency_p95")
                    && line.contains("trend: stable")) {

                Matcher matcher =
                        CURRENT_METRIC_PATTERN.matcher(line);

                if (matcher.find()) {
                    double latency =
                            Double.parseDouble(matcher.group(1));

                    stableLatency =
                            latency <= HEALTHY_LATENCY_THRESHOLD_MS;
                }
            }

            if (line.contains("error_rate")) {

                Matcher matcher =
                        ERROR_RATE_PATTERN.matcher(line);

                if (matcher.find()) {
                    double errorRate =
                            Double.parseDouble(matcher.group(1));

                    lowErrors =
                            errorRate
                                    <= LOW_ERROR_RATE_THRESHOLD_PERCENT;
                }
            }
        }

        return stableLatency && lowErrors;
    }

    /**
     * Detect whether the suspected service is itself a failed
     * dependency of another service.
     */
    private boolean isFailedUpstreamDependency(
            String service,
            String evidence) {

        String target = service.toLowerCase().trim();

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
     * Detect a false alarm. Missing data alone does not prove
     * that an incident is false.
     */
    private boolean isFalseAlarm(
            String evidence,
            String alertText) {

        String alert = alertText == null
                ? ""
                : alertText.toLowerCase();

        boolean activeHeapFailure =
                evidence.contains("highheapusage")
                        || evidence.contains("high heap")
                        || evidence.contains("heap_used_bytes")
                        || evidence.contains("heap usage")
                        || evidence.contains("heap above")
                        || evidence.contains("heap reached")
                        || evidence.contains("heap is rising")
                        || evidence.contains("heap rising")
                        || evidence.contains("memory usage")
                        || evidence.contains("outofmemory");

        boolean activeCpuFailure =
                evidence.contains("high cpu")
                        || evidence.contains("cpu usage")
                        || evidence.contains("cpu above")
                        || evidence.contains("cpu exhausted")
                        || evidence.contains("cpu exhaustion");

        boolean activeErrorFailure =
                evidence.contains("error rate")
                        && !evidence.contains("no error rate");

        boolean activeServiceFailure =
                evidence.contains("service down")
                        || evidence.contains("service is down")
                        || evidence.contains("unhealthy")
                        || evidence.contains("unreachable")
                        || evidence.contains("connection refused")
                        || evidence.contains("failed to connect")
                        || evidence.contains("unable to connect")
                        || evidence.contains("resourceaccessexception");

        boolean activeAlert =
                evidence.contains("alert fired")
                        || evidence.contains("alert firing")
                        || evidence.contains("firing for");

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
                evidence.contains("no error")
                        || evidence.contains("no errors")
                        || evidence.contains("no warn")
                        || evidence.contains("no warning")
                        || evidence.contains("no logs")
                        || evidence.contains("no error/warn")
                        || evidence.contains("no error or warn");

        boolean noData = evidence.contains("no data");

        boolean falling =
                evidence.contains("falling")
                        || evidence.contains("decreasing")
                        || evidence.contains("declining");

        boolean healthy =
                evidence.contains("health=up")
                        || evidence.contains("status=up")
                        || evidence.contains("healthy")
                        || evidence.contains("\"status\":\"up\"")
                        || evidence.contains("marked as up");

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

        String service = downstream.toLowerCase();

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

        String service = upstream.toLowerCase();

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
     * Check whether the evidence explicitly points to this service
     * as the failing dependency.
     */
    private boolean callerErrorsPointToService(
            String service,
            String evidence) {

        String target = service.toLowerCase().trim();

        return evidence.contains("errors pointing to " + target)
                || evidence.contains("error pointing to " + target)
                || evidence.contains("failed calls to " + target)
                || evidence.contains("failed call to " + target)
                || evidence.contains("calling " + target)
                || evidence.contains("calls to " + target);
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
