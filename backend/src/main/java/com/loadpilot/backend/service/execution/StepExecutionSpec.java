package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.CaptureSource;
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
        String captureJsonPath,
        /** Variables de chemin (objet JSON), voir RequestBuilder. */
        String pathParams,
        /** Parametres de requete (tableau JSON), voir RequestBuilder. */
        String queryParams,
        /** Source de la capture : null/JSON = chemin dans le corps ; HEADER = captureJsonPath est un
         * nom d'en-tete de reponse (voir ResponseHeaderExtractor). */
        CaptureSource captureSource,
        /** Temps de reponse maximal (ms) ; null = aucun controle (voir StepChecks). */
        Integer maxResponseTimeMs,
        /** Assertion JSON : champ, comparaison (null = EQUALS), valeur attendue ; champ null = aucune. */
        String assertionJsonPath,
        com.loadpilot.backend.enums.JsonAssertionOperator assertionJsonOperator,
        String assertionJsonValue
) {
    /** Compatibilite : etape sans temps de reponse maximal ni assertion JSON. */
    public StepExecutionSpec(UUID stepId, String name, HttpMethod method, String url, String headers, String body,
            Integer expectedStatus, Integer thinkTimeMs, Integer timeoutSeconds, Boolean followRedirects,
            String assertionBodyContains, String captureVariableName, String captureJsonPath, String pathParams,
            String queryParams, CaptureSource captureSource) {
        this(stepId, name, method, url, headers, body, expectedStatus, thinkTimeMs, timeoutSeconds, followRedirects,
                assertionBodyContains, captureVariableName, captureJsonPath, pathParams, queryParams, captureSource,
                null, null, null, null);
    }

    /** Compatibilite : etape sans parametres de chemin ni de requete separes. */
    public StepExecutionSpec(UUID stepId, String name, HttpMethod method, String url, String headers, String body,
            Integer expectedStatus, Integer thinkTimeMs, Integer timeoutSeconds, Boolean followRedirects,
            String assertionBodyContains, String captureVariableName, String captureJsonPath) {
        this(stepId, name, method, url, headers, body, expectedStatus, thinkTimeMs, timeoutSeconds, followRedirects,
                assertionBodyContains, captureVariableName, captureJsonPath, null, null);
    }

    /** Compatibilite : capture JSON (source non precisee). */
    public StepExecutionSpec(UUID stepId, String name, HttpMethod method, String url, String headers, String body,
            Integer expectedStatus, Integer thinkTimeMs, Integer timeoutSeconds, Boolean followRedirects,
            String assertionBodyContains, String captureVariableName, String captureJsonPath, String pathParams,
            String queryParams) {
        this(stepId, name, method, url, headers, body, expectedStatus, thinkTimeMs, timeoutSeconds, followRedirects,
                assertionBodyContains, captureVariableName, captureJsonPath, pathParams, queryParams, null);
    }

    /** Une assertion JSON est configuree (le corps de la reponse doit etre lu). */
    public boolean hasJsonAssertion() {
        return StepChecks.hasJsonAssertion(assertionJsonPath);
    }

    /** Capture lue dans un en-tete de reponse (et non dans le corps JSON). */
    public boolean capturesFromHeader() {
        return captureSource == CaptureSource.HEADER;
    }

    /** Capture lue dans le corps HTML (selecteur CSS, voir HtmlExtractor). */
    public boolean capturesFromHtml() {
        return captureSource == CaptureSource.HTML;
    }
}
