
package com.bhumi.commander.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.bhumi.commander.tools.ServiceRegistry;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

class ActionExecutorPostgresIntegrationTest {

    private static JdbcClient jdbc;
    private static ActionExecutor executor;
    private static ServiceRegistry registry;
    private static Verifier verifier;
    private static RemediationAction remediationAction;
    private static DataSource dataSource;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void initializeDatabase() {
        dataSource = SharedPostgres.dataSource();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbc = JdbcClient.create(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);

        registry = mock(ServiceRegistry.class);
        verifier = mock(Verifier.class);
        remediationAction = mock(RemediationAction.class);

        when(registry.known("order-service")).thenReturn(true);
        when(registry.known("payment-service")).thenReturn(true);
        when(registry.known("unknown-service")).thenReturn(false);

        executor = new ActionExecutor(
                jdbc,
                registry,
                verifier,
                txManager,
                remediationAction);
    }

    @BeforeEach
    void cleanTestData() {
        jdbc.sql("""
                DELETE FROM action_proposals
                WHERE incident_id IN (
                    SELECT id FROM incidents
                    WHERE title LIKE
                    'integration-test-action-executor-%'
                )
                """).update();

        jdbc.sql("""
                DELETE FROM incidents
                WHERE title LIKE
                'integration-test-action-executor-%'
                """).update();
    }

    private long createIncident(boolean resolved) {
        return jdbc.sql("""
                INSERT INTO incidents (title, status, resolved_at)
                VALUES (
                    :title,
                    CASE WHEN :resolved THEN 'RESOLVED' ELSE 'OPEN' END,
                    CASE WHEN :resolved THEN now() ELSE NULL END
                )
                RETURNING id
                """)
                .param(
                        "title",
                        "integration-test-action-executor-incident")
                .param("resolved", resolved)
                .query(Long.class)
                .single();
    }

    private long createProposal(
            long incidentId,
            String action,
            String service,
            String status) {

        return jdbc.sql("""
                INSERT INTO action_proposals
                    (incident_id, action_type, target_service,
                     rationale, status)
                VALUES
                    (:incidentId, :action, :service,
                     'integration-test', :status)
                RETURNING id
                """)
                .param("incidentId", incidentId)
                .param("action", action)
                .param("service", service)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    @Test
    void shouldReturnNotFoundForMissingProposal() {
        ActionExecutor.Result result = executor.execute(-999L);

        assertEquals(ActionExecutor.Outcome.NOT_FOUND, result.outcome());
    }

    @Test
    void shouldRejectPendingProposal() {
        long incidentId = createIncident(false);

        long proposalId = createProposal(
                incidentId,
                "RESTART_SERVICE",
                "order-service",
                "PENDING");

        ActionExecutor.Result result = executor.execute(proposalId);

        assertEquals(
                ActionExecutor.Outcome.NOT_APPROVED,
                result.outcome());
    }

    @Test
    void shouldBlockExecutionForResolvedIncident() {
        long incidentId = createIncident(true);

        long proposalId = createProposal(
                incidentId,
                "RESTART_SERVICE",
                "order-service",
                "APPROVED");

        ActionExecutor.Result result = executor.execute(proposalId);

        assertEquals(
                ActionExecutor.Outcome.INCIDENT_CLOSED,
                result.outcome());
    }

    @Test
    void shouldRejectUnsupportedActionWithoutExecutingDocker() {
        long incidentId = createIncident(false);

        long proposalId = createProposal(
                incidentId,
                "DELETE_DATABASE",
                "order-service",
                "APPROVED");

        ActionExecutor.Result result = executor.execute(proposalId);

        assertEquals(ActionExecutor.Outcome.FAILED, result.outcome());
    }

    @Test
    void shouldRejectUnknownServiceWithoutExecutingDocker() {
        long incidentId = createIncident(false);

        long proposalId = createProposal(
                incidentId,
                "RESTART_SERVICE",
                "unknown-service",
                "APPROVED");

        ActionExecutor.Result result = executor.execute(proposalId);

        assertEquals(ActionExecutor.Outcome.FAILED, result.outcome());
    }

    @Test
    void shouldApplyCooldownAfterRecentExecution() {
        long previousIncident = createIncident(false);

        jdbc.sql("""
                INSERT INTO action_proposals
                    (incident_id, action_type, target_service,
                     rationale, status, executed_at)
                VALUES
                    (:incidentId, 'RESTART_SERVICE', 'order-service',
                     'previous integration test', 'EXECUTED', now())
                """)
                .param("incidentId", previousIncident)
                .update();

        long currentIncident = createIncident(false);

        long proposalId = createProposal(
                currentIncident,
                "RESTART_SERVICE",
                "order-service",
                "APPROVED");

        ActionExecutor.Result result = executor.execute(proposalId);

        assertEquals(ActionExecutor.Outcome.COOLDOWN, result.outcome());
    }

    @Test
    void concurrentExecutionShouldExecuteActionOnlyOnce()
            throws Exception {

        long incidentId = createIncident(false);

        long proposalId = createProposal(
                incidentId,
                "RESTART_SERVICE",
                "order-service",
                "APPROVED");

        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch allowActionToFinish = new CountDownLatch(1);
        AtomicInteger actionCalls = new AtomicInteger(0);

        doAnswer(invocation -> {
            actionCalls.incrementAndGet();
            actionStarted.countDown();

            if (!allowActionToFinish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting to finish action");
            }

            return "test restart completed";
        }).when(remediationAction).restart("order-service");

        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<ActionExecutor.Result> first =
                    pool.submit(() -> executor.execute(proposalId));

            assertTrue(
                    actionStarted.await(5, TimeUnit.SECONDS),
                    "First execution did not start in time");

            Future<ActionExecutor.Result> second =
                    pool.submit(() -> executor.execute(proposalId));

            ActionExecutor.Result secondResult =
                    second.get(5, TimeUnit.SECONDS);

            assertEquals(
                    ActionExecutor.Outcome.NOT_APPROVED,
                    secondResult.outcome());

            assertEquals(1, actionCalls.get());

            allowActionToFinish.countDown();

            ActionExecutor.Result firstResult =
                    first.get(5, TimeUnit.SECONDS);

            assertEquals(
                    ActionExecutor.Outcome.EXECUTED,
                    firstResult.outcome());

            assertEquals(1, actionCalls.get());

        } finally {
            allowActionToFinish.countDown();
            pool.shutdownNow();
        }
    }
}
