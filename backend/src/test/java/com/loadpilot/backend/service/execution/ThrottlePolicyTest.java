package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Lecture de Retry-After (secondes, date HTTP), delai par defaut, plafond et valeurs par defaut du reglage. */
class ThrottlePolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-21T07:28:00Z");

    @Test
    void retryAfter_inSeconds_orAsAnHttpDate() {
        assertThat(ThrottlePolicy.parseRetryAfterMs("120", NOW)).isEqualTo(120_000L);
        assertThat(ThrottlePolicy.parseRetryAfterMs(" 0 ", NOW)).isZero();
        assertThat(ThrottlePolicy.parseRetryAfterMs("Wed, 21 Oct 2026 07:28:30 GMT", NOW)).isEqualTo(30_000L);
        assertThat(ThrottlePolicy.parseRetryAfterMs("Wed, 21 Oct 2026 07:27:00 GMT", NOW)).as("date passee").isZero();
        // Absent ou illisible : null (le delai par defaut s'applique).
        assertThat(ThrottlePolicy.parseRetryAfterMs(null, NOW)).isNull();
        assertThat(ThrottlePolicy.parseRetryAfterMs("", NOW)).isNull();
        assertThat(ThrottlePolicy.parseRetryAfterMs("-5", NOW)).isNull();
        assertThat(ThrottlePolicy.parseRetryAfterMs("demain", NOW)).isNull();
        assertThat(ThrottlePolicy.parseRetryAfterMs("1.5", NOW)).isNull();
    }

    @Test
    void delay_defaultWhenMissing_neverMoreThan300Seconds() {
        ThrottlePolicy policy = ThrottlePolicy.DEFAULT;
        assertThat(policy.delayMs(null, NOW)).isEqualTo(5_000L);
        assertThat(policy.delayMs("illisible", NOW)).isEqualTo(5_000L);
        assertThat(policy.delayMs("2", NOW)).isEqualTo(2_000L);
        assertThat(policy.delayMs("3600", NOW)).isEqualTo(300_000L);
        assertThat(policy.delayMs("99999999999999999999", NOW)).isEqualTo(300_000L);
        assertThat(policy.delayMs("Thu, 22 Oct 2026 07:28:00 GMT", NOW)).isEqualTo(300_000L);
    }

    @Test
    void scenarioSetting_activeByDefault_boundedValues() {
        assertThat(ThrottlePolicy.of(null, null, null)).isEqualTo(new ThrottlePolicy(true, 5, 3));
        assertThat(ThrottlePolicy.of(false, null, null).enabled()).isFalse();
        assertThat(ThrottlePolicy.of(true, 0, -1)).isEqualTo(new ThrottlePolicy(true, 1, 0));
        assertThat(ThrottlePolicy.of(true, 1000, 50)).isEqualTo(new ThrottlePolicy(true, 300, 10));
        assertThat(LoadTestSpec.singlePass().throttle()).isEqualTo(ThrottlePolicy.DEFAULT);

        StepOutcome tooMany = new StepOutcome(null, 429, 1, false, null, NOW);
        assertThat(ThrottlePolicy.DEFAULT.applies(tooMany)).isTrue();
        assertThat(ThrottlePolicy.DISABLED.applies(tooMany)).isFalse();
        assertThat(ThrottlePolicy.DEFAULT.applies(new StepOutcome(null, 503, 1, false, null, NOW))).isFalse();
        assertThat(ThrottlePolicy.DEFAULT.applies(new StepOutcome(null, null, 1, false, "timeout", NOW))).isFalse();
    }
}
