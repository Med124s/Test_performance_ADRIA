package com.loadpilot.backend.dto.response;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resultat detaille d'un test de verification (1 utilisateur virtuel, 1 passage) - voir
 * StepTestRunner. Jamais persiste : aucune Execution, aucune Metric. Les secrets
 * (Authorization, X-API-Key, Cookie/Set-Cookie, champs password/token, valeurs
 * d'authentification de l'application) sont deja masques ("••••••").
 */
public record TestRunResponse(
        UUID scenarioId,
        String applicationUrl,
        boolean success,
        int totalSteps,
        int executedSteps,
        int passedSteps,
        long totalDurationMs,
        boolean timedOut,
        AuthResult auth,
        List<StepResult> steps,
        /** Variables connues a la fin du test (CSV + captures), sans les valeurs sensibles -
         * reutilisables pour POST /api/steps/{id}/test-run. */
        Map<String, String> variables
) {

    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    /** Authentification de l'application (login fait une seule fois pour tout le test). */
    public record AuthResult(String type, boolean success, String message, long durationMs) {
    }

    public record StepResult(
            int index,
            UUID stepId,
            String name,
            /** PASSED, FAILED ou SKIPPED (non executee). */
            String status,
            boolean success,
            String error,
            /** TIMEOUT, CONNECTION_REFUSED, DNS, TLS, NETWORK, INVALID_REQUEST, UNRESOLVED_VARIABLE,
             * HTTP_STATUS, ASSERTION (contenu ou JSON), RESPONSE_TIME, AUTH_FAILED, GLOBAL_TIMEOUT, PREVIOUS_FAILED, INTERRUPTED. */
            String errorKind,
            SentRequest request,
            ReceivedResponse response,
            Checks checks,
            UrlWarning urlWarning,
            List<String> warnings
    ) {
    }

    public record SentRequest(String method, String url, Map<String, String> headers, String body,
            boolean followRedirects, long timeoutSeconds) {
    }

    public record ReceivedResponse(int status, String statusText, long durationMs, long sizeBytes,
            String contentType, Map<String, String> headers, String body, boolean truncated, boolean json) {
    }

    /** responseTime / json : null si le controle n'est pas configure sur l'etape. */
    public record Checks(StatusCheck status, AssertionCheck assertion, CaptureCheck capture,
            ResponseTimeCheck responseTime, JsonCheck json) {
        public Checks(StatusCheck status, AssertionCheck assertion, CaptureCheck capture) {
            this(status, assertion, capture, null, null);
        }
    }

    /** Temps de reponse maximal ; actualMs et ok null : non evalue (aucune reponse recue). */
    public record ResponseTimeCheck(int maxMs, Long actualMs, Boolean ok) {
    }

    /** Assertion JSON ; operator EQUALS / CONTAINS ; ok null : non evaluee ; message : raison de l'echec. */
    public record JsonCheck(String path, String operator, String expected, Boolean ok, String message) {
    }

    /** expected : code exact attendu, ou "2xx-3xx" sans code configure. */
    public record StatusCheck(String expected, Integer actual, boolean ok) {
    }

    /** ok null : non evaluee (aucune reponse recue). */
    public record AssertionCheck(String expectedContent, Boolean ok) {
    }

    public record CaptureCheck(String variableName, String jsonPath, boolean found, String value, boolean masked,
            String message) {
    }

    public record UrlWarning(String message, String suggestedPath) {
    }
}
