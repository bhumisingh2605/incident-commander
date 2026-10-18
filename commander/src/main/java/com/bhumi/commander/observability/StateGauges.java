package com.bhumi.commander.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class StateGauges {

    private static final List<String> INCIDENT_STATUSES = List.of(
            "OPEN", "INVESTIGATING", "DIAGNOSED", "AWAITING_APPROVAL", "REMEDIATING", "RESOLVED", "ESCALATED");
    private static final List<String> PROPOSAL_STATUSES = List.of(
            "PENDING", "APPROVED", "REJECTED", "EXECUTING", "EXECUTED", "FAILED", "INTERRUPTED", "EXPIRED");

    public StateGauges(MeterRegistry registry, JdbcClient jdbc) {
        for (String status : INCIDENT_STATUSES) {
            Gauge.builder("commander.incidents", jdbc, j -> countIncidents(j, status))
                    .tag("status", status).register(registry);
        }
        for (String status : PROPOSAL_STATUSES) {
            Gauge.builder("commander.proposals", jdbc, j -> countProposals(j, status))
                    .tag("status", status).register(registry);
        }
    }

    private static double countIncidents(JdbcClient j, String status) {
        try {
            Long n = j.sql("SELECT count(*) FROM incidents WHERE status = :s").param("s", status).query(Long.class).single();
            return n == null ? 0 : n;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static double countProposals(JdbcClient j, String status) {
        try {
            Long n = j.sql("SELECT count(*) FROM action_proposals WHERE status = :s").param("s", status).query(Long.class).single();
            return n == null ? 0 : n;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}