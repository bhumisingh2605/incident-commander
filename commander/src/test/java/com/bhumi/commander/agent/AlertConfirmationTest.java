package com.bhumi.commander.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bhumi.commander.remediation.ErrorRateProbe;
import com.bhumi.commander.remediation.LatencyProbe;
import com.bhumi.commander.tools.ServiceRegistry;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class AlertConfirmationTest {

    private static final String ERROR_ALERT =
            "HighErrorRate firing on order-service: Error rate above 5% on order-service";
    private static final String LATENCY_ALERT =
            "HighLatencyP95 firing on order-service: p95 latency above 1s on order-service";

    private final ErrorRateProbe errors = mock(ErrorRateProbe.class);
    private final LatencyProbe latency = mock(LatencyProbe.class);
    private final AlertConfirmation check = new AlertConfirmation(errors, latency, new ServiceRegistry());

    @Test
    void errorAlertIsDismissedWhenEveryServiceIsBelowTheThreshold() {
        when(errors.errorRate(anyString())).thenReturn(OptionalDouble.of(0.0));
        AnalystAgent.Result r = check.checkEarly("run1", ERROR_ALERT, System.nanoTime());
        assertThat(r).isNotNull();
        assertThat(r.rootCause().suspectedService()).isEqualTo("unknown");
        assertThat(r.rootCause().category()).isEqualTo("UNKNOWN");
        assertThat(r.rootCause().confidence()).isLessThan(0.4);
        assertThat(r.toolCalls()).isZero();
    }

    @Test
    void errorAlertIsKeptWhenADownstreamServiceIsFailing() {
        when(errors.errorRate("order-service")).thenReturn(OptionalDouble.of(0.0));
        when(errors.errorRate("payment-service")).thenReturn(OptionalDouble.of(0.5));
        assertThat(check.checkEarly("run1", ERROR_ALERT, System.nanoTime())).isNull();
    }

    @Test
    void noTrafficMeansTheAlertCannotBeRuledOut() {
        when(errors.errorRate(anyString())).thenReturn(OptionalDouble.empty());
        assertThat(check.checkEarly("run1", ERROR_ALERT, System.nanoTime())).isNull();
    }

    @Test
    void aFailingProbeMeansTheModelInvestigates() {
        when(errors.errorRate(anyString())).thenThrow(new RuntimeException("prometheus is down"));
        assertThat(check.checkEarly("run1", ERROR_ALERT, System.nanoTime())).isNull();
    }

    @Test
    void latencyAlertIsDismissedWhenP95IsBelowOneSecond() {
        when(latency.p95Seconds(anyString())).thenReturn(OptionalDouble.of(0.15));
        AnalystAgent.Result r = check.checkEarly("run1", LATENCY_ALERT, System.nanoTime());
        assertThat(r).isNotNull();
        assertThat(r.rootCause().category()).isEqualTo("UNKNOWN");
        verifyNoInteractions(errors);
    }

    @Test
    void latencyAlertIsKeptWhenARealServiceIsSlow() {
        when(latency.p95Seconds("order-service")).thenReturn(OptionalDouble.of(0.1));
        when(latency.p95Seconds("payment-service")).thenReturn(OptionalDouble.of(3.2));
        assertThat(check.checkEarly("run1", LATENCY_ALERT, System.nanoTime())).isNull();
    }

    @Test
    void otherAlertTypesAreLeftToTheModel() {
        assertThat(check.checkEarly("run1", "HighHeapUsage firing on payment-service: heap above 100 MB",
                System.nanoTime())).isNull();
    }

    @Test
    void anAlertWithoutAKnownServiceIsLeftToTheModel() {
        assertThat(check.checkEarly("run1", "HighErrorRate firing on mystery-service: error rate",
                System.nanoTime())).isNull();
    }
}