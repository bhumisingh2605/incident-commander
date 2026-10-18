package com.bhumi.commander.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Gives every request an ID and puts the incident or proposal ID from the URL into the log context. */
@Component("commanderRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);
    // Only digits are captured, so nothing from the URL can inject text into the logs.
    private static final Pattern ID_IN_PATH = Pattern.compile("^/api/(incidents|proposals)/(\\d{1,18})(?:/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("requestId", requestId);
        String idKey = null;
        Matcher m = ID_IN_PATH.matcher(req.getRequestURI());
        if (m.matches()) {
            idKey = m.group(1).equals("incidents") ? "incidentId" : "proposalId";
            MDC.put(idKey, m.group(2));
        }
        res.setHeader("X-Request-Id", requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            if (!"GET".equals(req.getMethod())) {
                String path = req.getRequestURI();
                if (path.length() > 120) path = path.substring(0, 120);
                log.info("{} {} -> {} ({} ms)", req.getMethod(), path, res.getStatus(),
                        (System.nanoTime() - start) / 1_000_000);
            }
            MDC.remove("requestId");
            if (idKey != null) MDC.remove(idKey);
        }
    }
}