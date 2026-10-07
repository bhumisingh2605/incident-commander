package com.bhumi.order_service.chaos;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ChaosFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ChaosFilter.class);
    private final ChaosState state;

    public ChaosFilter(ChaosState state) { this.state = state; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return p.startsWith("/chaos") || p.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (state.delayMs > 0) {
            try { Thread.sleep(state.delayMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (state.errorMode) {
            log.error("Unhandled error while processing {} {}", req.getMethod(), req.getRequestURI());
            res.setStatus(500);
            res.setContentType("text/plain");
            res.getWriter().write("chaos error mode");
            return;
        }
        chain.doFilter(req, res);
    }
}