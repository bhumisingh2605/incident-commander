package com.bhumi.commander.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bhumi.commander.tools.HealthTools;
import com.bhumi.commander.tools.ServiceRegistry;
import org.junit.jupiter.api.Test;

class ServiceDownCheckTest {

    private static final String ALERT =
            "HighErrorRate firing on order-service: Error rate above 5% on order-service";

    private final HealthTools health = mock(HealthTools.class);
    private final ServiceDownCheck check = new ServiceDownCheck(health, new ServiceRegistry());

    @Test
    void anUnreachableDownstreamServiceIsReportedAsDown() {
        when(health.getServiceHealth("order-service")).thenReturn("order-service health: UP");
        when(health.getServiceHealth("payment-service")).thenReturn("payment-service health: UNREACHABLE (no response)");
        AnalystAgent.Result r = check.checkEarly("run1", ALERT, System.nanoTime());
        assertThat(r).isNotNull();
        assertThat(r.rootCause().suspectedService()).isEqualTo("payment-service");
        assertThat(r.rootCause().category()).isEqualTo("SERVICE_DOWN");
        assertThat(r.rootCause().suggestedActions()).contains("RESTART_SERVICE payment-service");
        assertThat(r.toolCalls()).isZero();
    }

    @Test
    void whenEverythingIsUpTheModelInvestigates() {
        when(health.getServiceHealth("order-service")).thenReturn("order-service health: UP");
        when(health.getServiceHealth("payment-service")).thenReturn("payment-service health: UP");
        assertThat(check.checkEarly("run1", ALERT, System.nanoTime())).isNull();
    }

    @Test
    void theDownstreamServiceIsBlamedBeforeTheCaller() {
        when(health.getServiceHealth("order-service")).thenReturn("order-service health: UNREACHABLE (no response)");
        when(health.getServiceHealth("payment-service")).thenReturn("payment-service health: UNREACHABLE (no response)");
        assertThat(check.checkEarly("run1", ALERT, System.nanoTime()).rootCause().suspectedService())
                .isEqualTo("payment-service");
    }

    @Test
    void anHttpDownResponseCountsAsDown() {
        when(health.getServiceHealth("order-service")).thenReturn("order-service health: DOWN (HTTP 503)");
        when(health.getServiceHealth("payment-service")).thenReturn("payment-service health: UP");
        assertThat(check.checkEarly("run1", ALERT, System.nanoTime()).rootCause().suspectedService())
                .isEqualTo("order-service");
    }

    @Test
    void anUnrecognizedHealthAnswerIsNotTreatedAsDown() {
        when(health.getServiceHealth("order-service")).thenReturn("Unknown service 'order-service'");
        when(health.getServiceHealth("payment-service")).thenReturn("payment-service health: UP");
        assertThat(check.checkEarly("run1", ALERT, System.nanoTime())).isNull();
    }
}