package com.loadpilot.backend.service.execution;

import java.net.http.HttpHeaders;
import java.time.Instant;

/** Gestion du 429 Too Many Requests pour un utilisateur virtuel : delai de rejeu (Retry-After ou defaut) et nombre de rejeux. */
final class RetryAfterHandler {

    private final ThrottlePolicy policy;
    /** En-tete Retry-After du dernier 429 recu (null apres une autre reponse). */
    private String retryAfter;
    /** Rejeux deja faits pour l'etape en cours. */
    private int retries;

    RetryAfterHandler(ThrottlePolicy policy) {
        this.policy = policy;
    }

    /** Reponse recue : garde Retry-After si c'est un 429, l'oublie sinon. */
    void recordResponse(int statusCode, HttpHeaders headers) {
        retryAfter = statusCode == ThrottlePolicy.TOO_MANY_REQUESTS ? headers.firstValue("Retry-After").orElse(null) : null;
    }

    /** true si le resultat est un 429 respecte. */
    boolean throttled(StepOutcome outcome) {
        return policy.applies(outcome);
    }

    /** Delai (ms) avant de rejouer l'etape s'il reste des rejeux, sinon -1. */
    long replayDelayMs(StepOutcome outcome, Instant now) {
        if (!policy.applies(outcome) || retries >= policy.maxRetries()) return -1;
        retries++;
        return policy.delayMs(retryAfter, now);
    }

    /** Remet le compteur de rejeux a zero. */
    void reset() {
        retries = 0;
    }

    /** Dernier Retry-After memorise (tests). */
    String lastRetryAfter() {
        return retryAfter;
    }
}
