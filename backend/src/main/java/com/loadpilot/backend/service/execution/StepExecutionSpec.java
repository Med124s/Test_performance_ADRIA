package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.CaptureSource;
import com.loadpilot.backend.enums.HttpMethod;
import java.util.UUID;

/** Champs d'une etape necessaires a son execution, copies au lancement. */
public record StepExecutionSpec(
        UUID stepId,
        String name,
        HttpMethod method,
        String url,
        String headers,
        String body,
        Integer expectedStatus,
        /** Pause apres cette etape (ms) ; null = aucune. */
        Integer thinkTimeMs,
        /** null = timeout global. */
        Integer timeoutSeconds,
        /** null = redirections suivies. */
        Boolean followRedirects,
        /** Texte que le corps de la reponse doit contenir ; null = aucune verification. */
        String assertionBodyContains,
        /** Nom de la variable a capturer ; utilise seulement si captureJsonPath est renseigne. */
        String captureVariableName,
        /** Chemin de la valeur a capturer dans la reponse. */
        String captureJsonPath,
        /** Variables de chemin (objet JSON), voir RequestBuilder. */
        String pathParams,
        /** Parametres de requete (tableau JSON), voir RequestBuilder. */
        String queryParams,
        /** Source de la capture : null / JSON = corps ; HEADER = nom d'en-tete de reponse. */
        CaptureSource captureSource,
        /** Temps de reponse maximal (ms) ; null = aucun controle (voir StepChecks). */
        Integer maxResponseTimeMs,
        /** Assertion JSON : champ, comparaison (null = EQUALS), valeur attendue ; champ null = aucune. */
        String assertionJsonPath,
        com.loadpilot.backend.enums.JsonAssertionOperator assertionJsonOperator,
        String assertionJsonValue
) {
    public StepExecutionSpec(UUID stepId, String name, HttpMethod method, String url, String headers, String body,
            Integer expectedStatus, Integer thinkTimeMs, Integer timeoutSeconds, Boolean followRedirects,
            String assertionBodyContains, String captureVariableName, String captureJsonPath, String pathParams,
            String queryParams, CaptureSource captureSource) {
        this(stepId, name, method, url, headers, body, expectedStatus, thinkTimeMs, timeoutSeconds, followRedirects,
                assertionBodyContains, captureVariableName, captureJsonPath, pathParams, queryParams, captureSource,
                null, null, null, null);
    }

    public StepExecutionSpec(UUID stepId, String name, HttpMethod method, String url, String headers, String body,
            Integer expectedStatus, Integer thinkTimeMs, Integer timeoutSeconds, Boolean followRedirects,
            String assertionBodyContains, String captureVariableName, String captureJsonPath) {
        this(stepId, name, method, url, headers, body, expectedStatus, thinkTimeMs, timeoutSeconds, followRedirects,
                assertionBodyContains, captureVariableName, captureJsonPath, null, null);
    }

    public StepExecutionSpec(UUID stepId, String name, HttpMethod method, String url, String headers, String body,
            Integer expectedStatus, Integer thinkTimeMs, Integer timeoutSeconds, Boolean followRedirects,
            String assertionBodyContains, String captureVariableName, String captureJsonPath, String pathParams,
            String queryParams) {
        this(stepId, name, method, url, headers, body, expectedStatus, thinkTimeMs, timeoutSeconds, followRedirects,
                assertionBodyContains, captureVariableName, captureJsonPath, pathParams, queryParams, null);
    }

    /** true si une assertion JSON est configuree. */
    public boolean hasJsonAssertion() {
        return StepChecks.hasJsonAssertion(assertionJsonPath);
    }

    /** Capture lue dans un en-tete de reponse. */
    public boolean capturesFromHeader() {
        return captureSource == CaptureSource.HEADER;
    }

    /** Capture lue dans le corps HTML (selecteur CSS, voir HtmlExtractor). */
    public boolean capturesFromHtml() {
        return captureSource == CaptureSource.HTML;
    }
}
