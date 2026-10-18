package com.bhumi.commander.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RootCauseTest {

    private static RootCause with(String category, double confidence) {
        return new RootCause("summary", "payment-service", category, List.of("evidence"), confidence, List.of());
    }

    @Test
    void anInventedCategoryBecomesUnknown() {
        assertThat(with("TOTALLY_MADE_UP", 0.5).category()).isEqualTo("UNKNOWN");
    }

    @Test
    void categoryIsTrimmedAndUpperCased() {
        assertThat(with("  bad_deploy ", 0.5).category()).isEqualTo("BAD_DEPLOY");
    }

    @Test
    void aMissingCategoryBecomesUnknown() {
        assertThat(with(null, 0.5).category()).isEqualTo("UNKNOWN");
    }

    @Test
    void confidenceIsClampedToZeroAndOne() {
        assertThat(with("UNKNOWN", 1.7).confidence()).isEqualTo(1.0);
        assertThat(with("UNKNOWN", -3).confidence()).isEqualTo(0.0);
        assertThat(with("UNKNOWN", Double.NaN).confidence()).isEqualTo(0.0);
    }

    @Test
    void nullFieldsGetSafeDefaults() {
        RootCause rc = new RootCause(null, null, null, null, 0.5, null);
        assertThat(rc.summary()).isEmpty();
        assertThat(rc.suspectedService()).isEqualTo("unknown");
        assertThat(rc.evidence()).isEmpty();
        assertThat(rc.suggestedActions()).isEmpty();
    }

    @Test
    void listsAreCopiedSoLaterChangesToTheSourceDoNotLeakIn() {
        List<String> actions = new ArrayList<>(List.of("RESTART_SERVICE payment-service"));
        RootCause rc = new RootCause("s", "payment-service", "UNKNOWN", List.of(), 0.5, actions);
        actions.add("WIPE_DATA payment-service");
        assertThat(rc.suggestedActions()).hasSize(1);
        assertThatThrownBy(() -> rc.suggestedActions().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}