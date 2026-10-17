package com.bhumi.commander.remediation;

import com.bhumi.commander.agent.RootCause;
import com.bhumi.commander.remediation.ProposalService;
import com.bhumi.commander.tools.ServiceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProposalServiceTest {

    @Test
    void shouldNotCreateProposalsWhenRootCauseIsNull() {
        JdbcClient jdbc = mock(JdbcClient.class);
        ServiceRegistry services = mock(ServiceRegistry.class);

        ProposalService proposalService =
                new ProposalService(jdbc, services);

        int created = proposalService.createFrom(123L, null);

        assertEquals(0, created);
        verify(jdbc, never()).sql(anyString());
    }

    @Test
    void shouldNotCreateProposalsForUnknownRootCause() {
        JdbcClient jdbc = mock(JdbcClient.class);
        ServiceRegistry services = mock(ServiceRegistry.class);

        ProposalService proposalService =
                new ProposalService(jdbc, services);

        RootCause rootCause = new RootCause(
                "No active errors or traffic data detected",
                "unknown",
                "UNKNOWN",
                List.of("No current telemetry"),
                0.2,
                List.of("INVESTIGATE_MANUALLY")
        );

        int created = proposalService.createFrom(123L, rootCause);

        assertEquals(0, created);
        verify(jdbc, never()).sql(anyString());
    }

    @Test
    void shouldNotCreateProposalsForResolvedOrMissingIncident() {
        JdbcClient jdbc = mock(JdbcClient.class);
        ServiceRegistry services = mock(ServiceRegistry.class);

        ProposalService proposalService =
                new ProposalService(jdbc, services);

        RootCause rootCause = mock(RootCause.class);

        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);

        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<Long> query =
                (JdbcClient.MappedQuerySpec<Long>)
                        mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(eq("i"), eq(123L)))
                .thenReturn(statement);
        when(statement.query(Long.class)).thenReturn(query);
        when(query.single()).thenReturn(0L);

        int created = proposalService.createFrom(123L, rootCause);

        assertEquals(0, created);

        verify(jdbc, never()).sql(
                startsWith("INSERT INTO action_proposals")
        );
    }

    @Test
    void shouldCancelPendingProposalWhenIncidentIsResolved() {
        JdbcClient jdbc = mock(JdbcClient.class);
        ServiceRegistry services = mock(ServiceRegistry.class);

        ProposalService proposalService =
                new ProposalService(jdbc, services);

        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        JdbcClient.ResultQuerySpec resultQuery =
                mock(JdbcClient.ResultQuerySpec.class);

        Map<String, Object> row = new HashMap<>();
        row.put("status", "PENDING");
        row.put(
                "resolved_at",
                Timestamp.valueOf("2026-10-15 10:00:00")
        );

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(eq("id"), eq(42L)))
                .thenReturn(statement);
        when(statement.query()).thenReturn(resultQuery);
        when(resultQuery.listOfRows()).thenReturn(List.of(row));

        when(statement.param(eq("r"), anyString()))
                .thenReturn(statement);
        when(statement.param(eq("by"), eq("operator")))
                .thenReturn(statement);
        when(statement.update()).thenReturn(1);

        ProposalService.Outcome outcome =
                proposalService.decide(42L, true, "operator");

        assertEquals(
                ProposalService.Outcome.INCIDENT_RESOLVED,
                outcome
        );

        verify(jdbc, times(1)).sql(
                startsWith("UPDATE action_proposals")
        );
    }

    @Test
    void shouldApprovePendingProposalForUnresolvedIncident() {
        JdbcClient jdbc = mock(JdbcClient.class);
        ServiceRegistry services = mock(ServiceRegistry.class);

        ProposalService proposalService =
                new ProposalService(jdbc, services);

        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        JdbcClient.ResultQuerySpec resultQuery =
                mock(JdbcClient.ResultQuerySpec.class);

        Map<String, Object> row = new HashMap<>();
        row.put("status", "PENDING");
        row.put("resolved_at", null);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(eq("id"), eq(42L)))
                .thenReturn(statement);
        when(statement.query()).thenReturn(resultQuery);
        when(resultQuery.listOfRows()).thenReturn(List.of(row));

        when(statement.param(eq("st"), eq("APPROVED")))
                .thenReturn(statement);
        when(statement.param(eq("by"), eq("operator")))
                .thenReturn(statement);
        when(statement.update()).thenReturn(1);

        ProposalService.Outcome outcome =
                proposalService.decide(42L, true, "operator");

        assertEquals(ProposalService.Outcome.OK, outcome);

        verify(jdbc).sql(
                startsWith("UPDATE action_proposals p")
        );
    }

    @Test
    void shouldRejectPendingProposalForUnresolvedIncident() {
        JdbcClient jdbc = mock(JdbcClient.class);
        ServiceRegistry services = mock(ServiceRegistry.class);

        ProposalService proposalService =
                new ProposalService(jdbc, services);

        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        JdbcClient.ResultQuerySpec resultQuery =
                mock(JdbcClient.ResultQuerySpec.class);

        Map<String, Object> row = new HashMap<>();
        row.put("status", "PENDING");
        row.put("resolved_at", null);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(eq("id"), eq(43L)))
                .thenReturn(statement);
        when(statement.query()).thenReturn(resultQuery);
        when(resultQuery.listOfRows()).thenReturn(List.of(row));

        when(statement.param(eq("st"), eq("REJECTED")))
                .thenReturn(statement);
        when(statement.param(eq("by"), eq("operator")))
                .thenReturn(statement);
        when(statement.update()).thenReturn(1);

        ProposalService.Outcome outcome =
                proposalService.decide(43L, false, "operator");

        assertEquals(ProposalService.Outcome.OK, outcome);

        verify(jdbc).sql(
                startsWith("UPDATE action_proposals p")
        );
    }

    @Test
    void shouldReturnAlreadyDecidedWhenProposalIsNotPending() {
        JdbcClient jdbc = mock(JdbcClient.class);
        ServiceRegistry services = mock(ServiceRegistry.class);

        ProposalService proposalService =
                new ProposalService(jdbc, services);

        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        JdbcClient.ResultQuerySpec resultQuery =
                mock(JdbcClient.ResultQuerySpec.class);

        Map<String, Object> row = new HashMap<>();
        row.put("status", "APPROVED");
        row.put("resolved_at", null);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(eq("id"), eq(44L)))
                .thenReturn(statement);
        when(statement.query()).thenReturn(resultQuery);
        when(resultQuery.listOfRows()).thenReturn(List.of(row));

        ProposalService.Outcome outcome =
                proposalService.decide(44L, false, "operator");

        assertEquals(
                ProposalService.Outcome.ALREADY_DECIDED,
                outcome
        );

        verify(jdbc, never()).sql(
                startsWith("UPDATE action_proposals p")
        );
    }

    @Test
    void shouldReturnNotFoundWhenProposalDoesNotExist() {
        JdbcClient jdbc = mock(JdbcClient.class);
        ServiceRegistry services = mock(ServiceRegistry.class);

        ProposalService proposalService =
                new ProposalService(jdbc, services);

        JdbcClient.StatementSpec statement =
                mock(JdbcClient.StatementSpec.class);
        JdbcClient.ResultQuerySpec resultQuery =
                mock(JdbcClient.ResultQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(eq("id"), eq(999L)))
                .thenReturn(statement);
        when(statement.query()).thenReturn(resultQuery);
        when(resultQuery.listOfRows()).thenReturn(List.of());

        ProposalService.Outcome outcome =
                proposalService.decide(999L, true, "operator");

        assertEquals(
                ProposalService.Outcome.NOT_FOUND,
                outcome
        );

        verify(jdbc, never()).sql(
                startsWith("UPDATE action_proposals p")
        );
    }
}
