package com.bhumi.commander.agent;

import com.bhumi.commander.tools.ServiceRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deterministic checks on the model's answer.
 *
 * The model proposes the diagnosis.
 * This guardrail validates the diagnosis against collected evidence.
 */
@Component
public class RootCauseGuard {

    private static final Logger log =
            LoggerFactory.getLogger(RootCauseGuard.class);

    private static final int RECENT_DEPLOY_MINUTES = 30;

    private final JdbcClient jdbc;
    private final ServiceRegistry services;

    public RootCauseGuard(
            JdbcClient jdbc,
            ServiceRegistry services) {

        this.jdbc = jdbc;
        this.services = services;
    }

    public RootCause apply(
            RootCause rc,
            String alertText,
            String evidenceText) {

        /*
         * ============================================================
         * RULE 0: NULL MODEL RESPONSE
         * ============================================================
         */
        if (rc == null) {

            return new RootCause(
                    "No diagnosis was produced",
                    "unknown",
                    "UNKNOWN",
                    List.of(
                            "Guardrail: model returned null diagnosis"
                    ),
                    0.0,
                    List.of()
            );
        }

        String svc =
                rc.suspectedService();

        String evidence =
                evidenceText == null
                        ? ""
                        : evidenceText.toLowerCase();

        /*
         * ============================================================
         * RULE 1: FALSE ALARM
         * ============================================================
         */
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

        /*
         * If the model did not provide a usable service, there is
         * nothing else to validate.
         */
        if (svc == null
                || svc.equalsIgnoreCase("unknown")) {

            return rc;
        }

        if (!services.known(svc)) {
            return rc;
        }

        String category =
                rc.category();

        /*
         * ============================================================
         * RULE 2: DOWNSTREAM DEPENDENCY FAILURE
         * ============================================================
         *
         * Example:
         *
         * order-service -> payment-service
         *
         * If order-service is failing because payment-service is
         * unavailable, payment-service is the root cause.
         */
        for (String downstream :
                services.downstreamOf(svc)) {

            if (downstreamFailureIsVisible(
                    downstream,
                    evidence)
                    && upstreamFailureIsVisible(
                    svc,
                    evidence)) {

                List<String> updatedEvidence =
                        new ArrayList<>(rc.evidence());

                updatedEvidence.add(
                        "Guardrail: "
                                + downstream
                                + " is unhealthy/down while "
                                + svc
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
                        Math.max(
                                rc.confidence(),
                                0.9
                        ),
                        rc.suggestedActions()
                );
            }
        }

        /*
         * ============================================================
         * RULE 3: SERVICE-DOWN DEPENDENCY
         * ============================================================
         *
         * IMPORTANT S5 CASE
         *
         * order-service -> payment-service
         *
         * payment-service has NO downstream services.
         *
         * Therefore the old generic rule:
         *
         * "payment-service calls nobody, so DEPENDENCY_FAILURE
         *  must become APPLICATION_ERROR"
         *
         * is incorrect.
         *
         * payment-service itself can be the dependency that
         * order-service depends on.
         *
         * Evidence:
         *
         * order-service has a 100% error rate with errors
         * pointing to payment-service
         *
         * Therefore:
         *
         * payment-service -> SERVICE_DOWN
         */
        if (category.equals("DEPENDENCY_FAILURE")
                && isFailedUpstreamDependency(
                svc,
                evidence)) {

            List<String> updatedEvidence =
                    new ArrayList<>(rc.evidence());

            updatedEvidence.add(
                    "Guardrail: "
                            + svc
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
                    Math.max(
                            rc.confidence(),
                            0.9
                    ),
                    rc.suggestedActions()
            );
        }

        /*
         * ============================================================
         * RULE 4: RECENT DEPLOYMENT
         * ============================================================
         */
        String corrected = category;
        String reason = null;

        /*
         * If the suspected service was recently deployed and the
         * category is APPLICATION_ERROR or DEPENDENCY_FAILURE,
         * treat the deployment as a possible bad deployment.
         */
        if (hasRecentDeploy(svc)
                && (category.equals("APPLICATION_ERROR")
                || category.equals("DEPENDENCY_FAILURE"))) {

            corrected = "BAD_DEPLOY";

            reason =
                    svc
                            + " was deployed in the last "
                            + RECENT_DEPLOY_MINUTES
                            + " minutes";
        }

        /*
         * ============================================================
         * RULE 5: SERVICE WITH NO DOWNSTREAM
         * ============================================================
         *
         * If the service calls nobody else, it cannot have a
         * DEPENDENCY_FAILURE caused by its own downstream service.
         *
         * S5 has already been handled above.
         *
         * Remaining DEPENDENCY_FAILURE cases are therefore corrected
         * using the existing fallback behaviour.
         */
        else if (category.equals("DEPENDENCY_FAILURE")
                && services.downstreamOf(svc).isEmpty()) {

            corrected =
                    alertText != null
                            && alertText
                            .toLowerCase()
                            .contains("latency")
                            ? "SLOW_DEPENDENCY"
                            : "APPLICATION_ERROR";

            reason =
                    svc
                            + " calls no other service, so it "
                            + "cannot fail because of a dependency";
        }

        /*
         * ============================================================
         * NO CHANGE REQUIRED
         * ============================================================
         */
        if (corrected.equals(category)) {
            return rc;
        }

        List<String> updatedEvidence =
                new ArrayList<>(rc.evidence());

        updatedEvidence.add(
                "Guardrail: category changed from "
                        + category
                        + " to "
                        + corrected
                        + " ("
                        + reason
                        + ")"
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
     * Detects whether the suspected service is itself a failed
     * dependency of another service.
     *
     * This specifically handles S5:
     *
     * order-service -> payment-service
     *
     * Evidence:
     * order-service has errors pointing to payment-service
     */
    private boolean isFailedUpstreamDependency(
            String service,
            String evidence) {

        String target =
                service.toLowerCase().trim();

        /*
         * ============================================================
         * DIRECT EVIDENCE
         * ============================================================
         *
         * These phrases explicitly identify the suspected service
         * as the failed dependency.
         */
        boolean callerPointsToService =
                evidence.contains(
                        "errors pointing to " + target
                )
                        || evidence.contains(
                        "error pointing to " + target
                )
                        || evidence.contains(
                        "failed calls to " + target
                )
                        || evidence.contains(
                        "failed call to " + target
                )
                        || evidence.contains(
                        "calls to " + target
                )
                        || evidence.contains(
                        "calling " + target
                );

        /*
         * ============================================================
         * SERVICE-DOWN SIGNALS
         * ============================================================
         */
        boolean serviceLooksDown =
                evidence.contains("unreachable")
                        || evidence.contains("connection refused")
                        || evidence.contains("service down")
                        || evidence.contains("service is down")
                        || evidence.contains("not running")
                        || evidence.contains("status=down")
                        || evidence.contains("health=down")
                        || evidence.contains("no response");

        /*
         * ============================================================
         * TOPOLOGY CONFIRMATION
         * ============================================================
         *
         * If ServiceRegistry knows that another service depends
         * on this service, that strengthens the conclusion.
         */
        boolean hasUpstreamCaller =
                !services.upstreamOf(target).isEmpty();

        /*
         * Strongest case:
         *
         * another service depends on this service AND
         * evidence points to this service.
         */
        if (hasUpstreamCaller
                && (callerPointsToService
                || serviceLooksDown)) {

            return true;
        }

        /*
         * Even if topology information is unavailable,
         * explicit evidence such as:
         *
         * "errors pointing to payment-service"
         *
         * is sufficient to identify the failed dependency.
         */
        return callerPointsToService;
    }

    /**
     * Detects a situation where there is no convincing evidence
     * of an active incident.
     *
     * "No data" does not automatically mean "no incident".
     */
    private boolean isFalseAlarm(
            String evidence,
            String alertText) {

        String alert =
                alertText == null
                        ? ""
                        : alertText.toLowerCase();

        /*
         * ============================================================
         * ACTIVE FAILURE SIGNALS
         * ============================================================
         */

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

        /*
         * The alert itself can also indicate an active incident.
         */
        boolean alertIndicatesActiveFailure =
                alert.contains("firing")
                        || alert.contains("highheapusage")
                        || alert.contains("higherrorrate")
                        || alert.contains("highcpu")
                        || alert.contains("memory")
                        || alert.contains("heap")
                        || alert.contains("cpu");

        if (activeFailure
                || alertIndicatesActiveFailure) {

            return false;
        }

        /*
         * ============================================================
         * NORMAL / FALSE-ALARM SIGNALS
         * ============================================================
         */

        boolean noErrors =
                evidence.contains("no error")
                        || evidence.contains("no errors")
                        || evidence.contains("no warn")
                        || evidence.contains("no warning")
                        || evidence.contains("no logs")
                        || evidence.contains("no error/warn")
                        || evidence.contains("no error or warn");

        boolean noData =
                evidence.contains("no data");

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

        /*
         * ============================================================
         * S8 FALSE ALARM
         * ============================================================
         */
        if (noErrors && noData) {
            return true;
        }

        /*
         * ============================================================
         * RECOVERY / FALSE-ALARM CASE
         * ============================================================
         */
        return noErrors
                && falling
                && healthy;
    }

    /**
     * Checks whether the downstream service has evidence
     * of being unavailable.
     */
    private boolean downstreamFailureIsVisible(
            String downstream,
            String evidence) {

        String service =
                downstream.toLowerCase();

        boolean mentionsService =
                evidence.contains(service);

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
     * Checks whether the upstream service is experiencing
     * connection/I/O failures caused by its dependency.
     */
    private boolean upstreamFailureIsVisible(
            String upstream,
            String evidence) {

        String service =
                upstream.toLowerCase();

        boolean mentionsService =
                evidence.contains(service);

        boolean dependencyError =
                evidence.contains("resourceaccess")
                        || evidence.contains("connection refused")
                        || evidence.contains("connection reset")
                        || evidence.contains("i/o error")
                        || evidence.contains("io error")
                        || evidence.contains("timeout")
                        || evidence.contains("failed to connect")
                        || evidence.contains("unable to connect");

        return mentionsService
                && dependencyError;
    }

    /**
     * Checks whether another service is reporting errors
     * that specifically point to this service as the
     * failing dependency.
     */
    private boolean callerErrorsPointToService(
            String service,
            String evidence) {

        String target =
                service.toLowerCase().trim();

        return evidence.contains(
                "errors pointing to " + target
        )
                || evidence.contains(
                "error pointing to " + target
        )
                || evidence.contains(
                "failed calls to " + target
        )
                || evidence.contains(
                "failed call to " + target
        )
                || evidence.contains(
                "calling " + target
        )
                || evidence.contains(
                "calls to " + target
        );
    }

    /**
     * Checks whether the service has a deployment within
     * the recent-deployment window.
     */
    private boolean hasRecentDeploy(
            String service) {

        Long n =
                jdbc.sql(
                                "SELECT count(*) "
                                        + "FROM deployments "
                                        + "WHERE service = :s "
                                        + "AND deployed_at > :since"
                        )
                        .param("s", service)
                        .param(
                                "since",
                                OffsetDateTime.now()
                                        .minusMinutes(
                                                RECENT_DEPLOY_MINUTES
                                        )
                        )
                        .query(Long.class)
                        .single();

        return n != null && n > 0;
    }
}