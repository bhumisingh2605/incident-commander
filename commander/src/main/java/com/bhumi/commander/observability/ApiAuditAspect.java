package com.bhumi.commander.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ApiAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(ApiAuditAspect.class);
    private final JdbcClient jdbc;

    public ApiAuditAspect(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Around("execution(public * com.bhumi.commander.api.ProposalController.approve(..))"
            + " || execution(public * com.bhumi.commander.api.ProposalController.reject(..))"
            + " || execution(public * com.bhumi.commander.api.ProposalController.execute(..))")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        String action = "API_" + pjp.getSignature().getName().toUpperCase();
        Long proposalId = null;
        String actor = "unknown";
        for (Object arg : pjp.getArgs()) {
            if (arg instanceof Long l) proposalId = l;
            if (arg instanceof Authentication a) actor = a.getName();
        }
        String previous = MDC.get("actor");
        MDC.put("actor", actor);
        String detail = null;
        try {
            Object out = pjp.proceed();
            if (out instanceof ResponseEntity<?> re) {
                detail = "HTTP " + re.getStatusCode().value()
                        + (re.getBody() instanceof String s ? ": " + (s.length() > 250 ? s.substring(0, 250) : s) : "");
            }
            return out;
        } catch (Throwable t) {
            detail = "exception: " + t.getClass().getSimpleName();
            throw t;
        } finally {
            record(actor, action, proposalId, detail);
            if (previous == null) MDC.remove("actor"); else MDC.put("actor", previous);
        }
    }

    private void record(String actor, String action, Long proposalId, String detail) {
        if (proposalId == null) return;
        try {
            jdbc.sql("""
                    INSERT INTO audit_events (actor, action, subject_type, subject_id, detail)
                    VALUES (:actor, :action, 'PROPOSAL', :id, :detail)
                    """)
                    .param("actor", actor).param("action", action).param("id", proposalId).param("detail", detail)
                    .update();
        } catch (Exception e) {
            log.error("Could not write audit event {} by {} for proposal {}: {}", action, actor, proposalId, e.toString());
        }
    }
}