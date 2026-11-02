package com.loadpilot.backend.service.execution;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Ralentissement sur 429 Too Many Requests : l'utilisateur virtuel attend le delai demande (Retry-After) ou le delai par defaut, puis rejoue l'etape. */
public record ThrottlePolicy(boolean enabled, int defaultDelaySeconds, int maxRetries) {

    public static final int TOO_MANY_REQUESTS = 429;
    public static final int DEFAULT_DELAY_SECONDS = 5;
    public static final int DEFAULT_MAX_RETRIES = 3;
    /** Attente maximale, meme si la cible demande davantage. */
    public static final int MAX_DELAY_SECONDS = 300;

    /** Valeurs par defaut : actif, 5 s, 3 rejeux. */
    public static final ThrottlePolicy DEFAULT = new ThrottlePolicy(true, DEFAULT_DELAY_SECONDS, DEFAULT_MAX_RETRIES);
    public static final ThrottlePolicy DISABLED = new ThrottlePolicy(false, DEFAULT_DELAY_SECONDS, DEFAULT_MAX_RETRIES);

    /** Reglage du scenario ; null = valeur par defaut. */
    public static ThrottlePolicy of(Boolean enabled, Integer defaultDelaySeconds, Integer maxRetries) {
        return new ThrottlePolicy(!Boolean.FALSE.equals(enabled),
                defaultDelaySeconds == null ? DEFAULT_DELAY_SECONDS : Math.max(1, Math.min(MAX_DELAY_SECONDS, defaultDelaySeconds)),
                maxRetries == null ? DEFAULT_MAX_RETRIES : Math.max(0, Math.min(10, maxRetries)));
    }

    /** true si le resultat est un 429 a respecter. */
    public boolean applies(StepOutcome outcome) {
        return enabled && outcome != null && Integer.valueOf(TOO_MANY_REQUESTS).equals(outcome.httpStatus());
    }

    /** Attente (ms) avant de rejouer : Retry-After s'il est lisible, sinon le delai par defaut ; au plus MAX_DELAY_SECONDS. */
    public long delayMs(String retryAfter, Instant now) {
        Long requested = parseRetryAfterMs(retryAfter, now);
        long ms = requested != null ? requested : defaultDelaySeconds * 1000L;
        return Math.max(0L, Math.min(MAX_DELAY_SECONDS * 1000L, ms));
    }

    /** Retry-After en millisecondes : nombre de secondes ou date HTTP ; null si absent ou illisible. */
    public static Long parseRetryAfterMs(String retryAfter, Instant now) {
        if (retryAfter == null || retryAfter.isBlank()) return null;
        String value = retryAfter.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Math.multiplyExact(Long.parseLong(value), 1000L);
            } catch (ArithmeticException | NumberFormatException e) {
                return MAX_DELAY_SECONDS * 1000L;
            }
        }
        try {
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Math.max(0L, Duration.between(now, at).toMillis());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
