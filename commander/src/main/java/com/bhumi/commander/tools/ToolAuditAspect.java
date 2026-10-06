package com.bhumi.commander.tools;

import com.bhumi.commander.agent.RunContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ToolAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditAspect.class);
    private static final int MAX_SUMMARY = 500;

    private final JdbcClient jdbc;

    public ToolAuditAspect(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        RunContext run = RunContext.current();
        String runId = run == null ? null : run.runId();

        if (run != null && run.increment() > RunContext.MAX_TOOL_CALLS) {
            String msg = "Tool call limit of " + RunContext.MAX_TOOL_CALLS
                    + " reached. Do not call more tools. Write your findings now.";
            save(pjp, "BLOCKED", msg, 0, runId);
            return msg;
        }

        long start = System.nanoTime();
        String status = "OK";
        String result = null;
        try {
            Object out = pjp.proceed();
            result = String.valueOf(out);
            return out;
        } catch (Throwable t) {
            status = "ERROR";
            result = t.getClass().getSimpleName() + ": " + t.getMessage();
            throw t;
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            save(pjp, status, result, ms, runId);
        }
    }

    // Auditing must never break a tool call, so failures here are only logged.
    private void save(ProceedingJoinPoint pjp, String status, String result, long ms, String runId) {
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            String[] names = sig.getParameterNames();
            Object[] values = pjp.getArgs();
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                if (i > 0) args.append(", ");
                args.append(names != null ? names[i] : "arg" + i).append('=').append(values[i]);
            }
            String summary = result == null ? null
                    : (result.length() <= MAX_SUMMARY ? result : result.substring(0, MAX_SUMMARY) + "...");

            jdbc.sql("""
                INSERT INTO tool_calls (run_id, tool_name, args, result_summary, status, duration_ms)
                VALUES (:run, :tool, :args, :result, :status, :ms)
                """)
                    .param("run", runId)
                    .param("tool", sig.getName())
                    .param("args", args.toString())
                    .param("result", summary)
                    .param("status", status)
                    .param("ms", ms)
                    .update();
        } catch (Exception e) {
            log.warn("Could not write tool audit entry: {}", e.getMessage());
        }
    }
}