package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.HttpMethod;
import java.util.UUID;

/**
 * Copie immuable des champs d'un Step necessaires a son execution reelle -
 * jamais l'entite JPA elle-meme (voir ExecutionServiceImpl : l'engine
 * s'execute HORS de toute transaction, pendant potentiellement plusieurs
 * vraies requetes HTTP ; lui passer une entite geree exposerait a un
 * LazyInitializationException).
 */
public record StepExecutionSpec(
        UUID stepId,
        String name,
        HttpMethod method,
        String url,
        String headers,
        String body,
        Integer expectedStatus,
        /** P1-Q Etape B — null = pas de pause supplementaire apres cette
         * etape (en plus du think time de Scenario, applique separement
         * apres l'iteration complete). */
        Integer thinkTimeMs,

        /** Passage produit reel (2026-09-30) — pause reelle APRES l'envoi
         * de la requete de cette etape, en plus de thinkTimeMs (qui reste
         * une pause AVANT la requete SUIVANTE). null = aucune pause
         * supplementaire. Voir HttpClientExecutionEngine. */
        Integer pacingAfterMs,
        /** P1-Q Etape B — null = utilise le timeout global du moteur. */
        Integer timeoutSeconds,
        /** P1-Q Etape B — null = comportement global (NORMAL). */
        Boolean followRedirects,
        /** P1-Q Etape B — null = aucune assertion de contenu (comportement
         * historique, corps de reponse jamais lu). */
        String assertionBodyContains,
        /** Master prompt final (Lot B) — nom de variable a produire depuis
         * la reponse de cette etape ; n'a d'effet que si captureJsonPath
         * est AUSSI non-null. */
        String captureVariableName,
        /** Master prompt final (Lot B) — chemin (sous-ensemble simple, voir
         * JsonPathExtractor) dans le corps JSON de la reponse. */
        String captureJsonPath
) {
    /** Compatibilite : ancienne forme sans pacingAfterMs (avant le passage
     * produit reel du 2026-09-30) - null = aucune pause supplementaire,
     * comportement historique inchange. */
    public StepExecutionSpec(UUID stepId, String name, HttpMethod method, String url, String headers, String body,
            Integer expectedStatus, Integer thinkTimeMs, Integer timeoutSeconds, Boolean followRedirects,
            String assertionBodyContains, String captureVariableName, String captureJsonPath) {
        this(stepId, name, method, url, headers, body, expectedStatus, thinkTimeMs, null, timeoutSeconds,
                followRedirects, assertionBodyContains, captureVariableName, captureJsonPath);
    }
}
