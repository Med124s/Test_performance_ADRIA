package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 429 Too Many Requests d'un VU : en-tete Retry-After memorise, rejeux bornes, remise a zero. */
class RetryAfterHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    private static StepOutcome outcome(Integer status) {
        return new StepOutcome(UUID.randomUUID(), status, 5, status != null && status < 400, null, NOW);
    }

    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (name, value) -> true);
    }

    @Test
    void retryAfterIsKeptOnlyFromA429_andForgottenAfterAnotherResponse() {
        RetryAfterHandler handler = new RetryAfterHandler(ThrottlePolicy.DEFAULT);
        handler.recordResponse(429, headers(Map.of("Retry-After", List.of("2"))));
        assertThat(handler.lastRetryAfter()).isEqualTo("2");
        handler.recordResponse(200, headers(Map.of("Retry-After", List.of("9"))));
        assertThat(handler.lastRetryAfter()).as("ignore hors 429").isNull();
        handler.recordResponse(429, headers(Map.of()));
        assertThat(handler.lastRetryAfter()).isNull();
    }

    @Test
    void replayDelay_followsRetryAfter_thenTheDefaultDelay() {
        RetryAfterHandler handler = new RetryAfterHandler(new ThrottlePolicy(true, 7, 3));
        handler.recordResponse(429, headers(Map.of("Retry-After", List.of("2"))));
        assertThat(handler.replayDelayMs(outcome(429), NOW)).isEqualTo(2_000);
        handler.recordResponse(429, headers(Map.of()));
        assertThat(handler.replayDelayMs(outcome(429), NOW)).as("delai par defaut").isEqualTo(7_000);
    }

    @Test
    void replaysAreBoundedByMaxRetries_andResetStartsAgain() {
        RetryAfterHandler handler = new RetryAfterHandler(new ThrottlePolicy(true, 0, 2));
        assertThat(handler.replayDelayMs(outcome(429), NOW)).isZero();
        assertThat(handler.replayDelayMs(outcome(429), NOW)).isZero();
        assertThat(handler.replayDelayMs(outcome(429), NOW)).as("3e rejeu refuse").isEqualTo(-1);
        handler.reset();
        assertThat(handler.replayDelayMs(outcome(429), NOW)).as("etape ou iteration suivante").isZero();
    }

    @Test
    void otherResponses_orADisabledPolicy_areNeverReplayed() {
        RetryAfterHandler handler = new RetryAfterHandler(ThrottlePolicy.DEFAULT);
        assertThat(handler.throttled(outcome(500))).isFalse();
        assertThat(handler.replayDelayMs(outcome(500), NOW)).isEqualTo(-1);
        assertThat(handler.replayDelayMs(outcome(null), NOW)).isEqualTo(-1);

        RetryAfterHandler disabled = new RetryAfterHandler(new ThrottlePolicy(false, 5, 3));
        assertThat(disabled.throttled(outcome(429))).isFalse();
        assertThat(disabled.replayDelayMs(outcome(429), NOW)).isEqualTo(-1);
    }
}
