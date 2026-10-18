package com.bhumi.commander.observability;

import com.bhumi.commander.agent.AnalystAgent;
import com.bhumi.commander.remediation.ActionExecutor;
import com.bhumi.commander.tools.ServiceRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ObservabilityAspect {

    private final MeterRegistry registry;
    private final ServiceRegistry services;

    public ObservabilityAspect(MeterRegistry registry, ServiceRegistry services) {
        this.registry = registry;
        this.services = services;
    }

    @Around("execution(* com.bhumi.commander.agent.FastAnalyst.analyze(String, String))")
    public Object analysis(ProceedingJoinPoint pjp) throws Throwable {
        String previous = MDC.get("runId");
        MDC.put("runId", String.valueOf(pjp.getArgs()[0]));
        long start = System.nanoTime();
        String path = "model";
        String outcome = "failed";
        try {
            Object out = pjp.proceed();
            if (out instanceof AnalystAgent.Result r) {
                outcome = "success";
                path = r.toolCalls() == 0 ? "rules_only" : "model";
                String service = services.known(r.rootCause().suspectedService()) ? r.rootCause().suspectedService() : "other";
                registry.summary("commander.analysis.tool.calls").record(r.toolCalls());
                registry.counter("commander.analysis.result", "service", service, "category", r.rootCause().category()).increment();
            }
            return out;
        } finally {
            registry.timer("commander.analysis.duration", "path", path, "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            restore("runId", previous);
        }
    }

    @Around("execution(* com.bhumi.commander.remediation.ActionExecutor.execute(long))")
    public Object remediation(ProceedingJoinPoint pjp) throws Throwable {
        String previous = MDC.get("proposalId");
        MDC.put("proposalId", String.valueOf(pjp.getArgs()[0]));
        long start = System.nanoTime();
        String outcome = "EXCEPTION";
        try {
            Object out = pjp.proceed();
            if (out instanceof ActionExecutor.Result r) outcome = r.outcome().name();
            return out;
        } finally {
            registry.counter("commander.remediation", "outcome", outcome).increment();
            registry.timer("commander.remediation.duration", "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            restore("proposalId", previous);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) MDC.remove(key); else MDC.put(key, previous);
    }
}