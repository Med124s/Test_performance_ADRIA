package com.loadpilot.backend.service.execution;

import java.net.http.HttpHeaders;
import java.time.Instant;

/**
 * Gestion du 429 Too Many Requests pour UN utilisateur virtuel (jamais partagee entre VU) : memorise
 * l'en-tete Retry-After de la derniere reponse 429, et decide si l'etape doit etre rejouee et apres
 * quel delai, dans la limite de ThrottlePolicy.maxRetries() rejeux consecutifs pour une meme etape.
 *
 * Utilisee par VirtualUserLoop (decision de rejeu) et par HttpClientExecutionEngine (lecture de
 * l'en-tete, seul endroit ou les en-tetes de la reponse sont disponibles). Les regles elles-memes
 * (429 concerne, delai par defaut, plafond) restent dans ThrottlePolicy.
 */
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

    /** Resultat ralenti par la cible (429 respecte) : categorie a part dans le suivi en direct. */
    boolean throttled(StepOutcome outcome) {
        return policy.applies(outcome);
    }

    /**
     * Delai (ms) avant de rejouer l'etape - Retry-After, ou delai par defaut - si elle est ralentie
     * et qu'il reste des rejeux ; -1 sinon. Un rejeu accorde est compte.
     */
    long replayDelayMs(StepOutcome outcome, Instant now) {
        if (!policy.applies(outcome) || retries >= policy.maxRetries()) return -1;
        retries++;
        return policy.delayMs(retryAfter, now);
    }

    /** Etape terminee sans rejeu, ou nouvelle iteration : le compteur de rejeux repart de zero. */
    void reset() {
        retries = 0;
    }

    /** Dernier Retry-After memorise (tests). */
    String lastRetryAfter() {
        return retryAfter;
    }
}
