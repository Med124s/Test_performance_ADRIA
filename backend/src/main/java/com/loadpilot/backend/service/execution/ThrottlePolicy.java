package com.loadpilot.backend.service.execution;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Ralentissement sur 429 Too Many Requests (reglage du scenario, ACTIF par defaut) : l'utilisateur
 * virtuel qui recoit un 429 attend le delai demande par la cible (en-tete Retry-After, en secondes
 * ou en date HTTP), ou le delai par defaut sans en-tete, puis rejoue la meme etape (session et
 * captures conservees), au plus "maxRetries" fois. Les autres VU ne sont pas ralentis.
 *
 * Le 429 reste un echec dans les resultats (requete non servie : statut final, rapport), mais il
 * est exclu du calcul du frein automatique (ErrorRateBrake) tant que le ralentissement est actif :
 * LoadPilot obeit deja a la demande de la cible, le frein juge les vraies erreurs. Desactive (test
 * de resilience), un 429 est un echec ordinaire, compte par le frein.
 */
public record ThrottlePolicy(boolean enabled, int defaultDelaySeconds, int maxRetries) {

    public static final int TOO_MANY_REQUESTS = 429;
    public static final int DEFAULT_DELAY_SECONDS = 5;
    public static final int DEFAULT_MAX_RETRIES = 3;
    /** Attente la plus longue acceptee, meme si la cible demande davantage (Retry-After: 3600...). */
    public static final int MAX_DELAY_SECONDS = 300;

    /** Defaut d'un scenario qui n'a rien regle : actif, 5 s, 3 rejeux. */
    public static final ThrottlePolicy DEFAULT = new ThrottlePolicy(true, DEFAULT_DELAY_SECONDS, DEFAULT_MAX_RETRIES);
    public static final ThrottlePolicy DISABLED = new ThrottlePolicy(false, DEFAULT_DELAY_SECONDS, DEFAULT_MAX_RETRIES);

    /** Reglage du scenario ; null = valeur par defaut. */
    public static ThrottlePolicy of(Boolean enabled, Integer defaultDelaySeconds, Integer maxRetries) {
        return new ThrottlePolicy(!Boolean.FALSE.equals(enabled),
                defaultDelaySeconds == null ? DEFAULT_DELAY_SECONDS : Math.max(1, Math.min(MAX_DELAY_SECONDS, defaultDelaySeconds)),
                maxRetries == null ? DEFAULT_MAX_RETRIES : Math.max(0, Math.min(10, maxRetries)));
    }

    /** Ce resultat est-il un 429 a respecter (attente, rejeu, hors calcul du frein) ? */
    public boolean applies(StepOutcome outcome) {
        return enabled && outcome != null && Integer.valueOf(TOO_MANY_REQUESTS).equals(outcome.httpStatus());
    }

    /**
     * Attente (ms) avant de rejouer : Retry-After s'il est lisible, sinon le delai par defaut ;
     * jamais negative, jamais plus de MAX_DELAY_SECONDS.
     */
    public long delayMs(String retryAfter, Instant now) {
        Long requested = parseRetryAfterMs(retryAfter, now);
        long ms = requested != null ? requested : defaultDelaySeconds * 1000L;
        return Math.max(0L, Math.min(MAX_DELAY_SECONDS * 1000L, ms));
    }

    /**
     * Les deux formes standard de Retry-After (RFC 9110, 10.2.3) : un nombre de secondes
     * (« 120 ») ou une date HTTP (« Wed, 21 Oct 2026 07:28:00 GMT ») - delai jusqu'a cette date,
     * 0 si elle est passee. null si absent ou illisible (le delai par defaut s'applique).
     */
    public static Long parseRetryAfterMs(String retryAfter, Instant now) {
        if (retryAfter == null || retryAfter.isBlank()) return null;
        String value = retryAfter.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Math.multiplyExact(Long.parseLong(value), 1000L);
            } catch (ArithmeticException | NumberFormatException e) {
                return MAX_DELAY_SECONDS * 1000L; // nombre gigantesque : plafonne
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
