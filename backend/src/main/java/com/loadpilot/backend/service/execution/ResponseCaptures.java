package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpHeaders;

/**
 * Capture d'une valeur dans la reponse d'une etape, pour les etapes suivantes du MEME utilisateur
 * virtuel : selon la source configuree, chemin dans le corps JSON (JsonPathExtractor), en-tete ou
 * cookie de la reponse (ResponseHeaderExtractor), ou element de la page HTML (HtmlExtractor).
 * Valeur introuvable : aucune variable produite, jamais une erreur d'execution.
 */
final class ResponseCaptures {

    /** Valeur capturee (null si introuvable) ; sensitive : a masquer dans le detail des requetes. */
    record Captured(String value, boolean sensitive) {
        static final Captured NONE = new Captured(null, false);
    }

    private ResponseCaptures() {
    }

    /** Capture configuree : nom de variable ET chemin renseignes (jamais automatique). */
    static boolean configured(StepExecutionSpec step) {
        return step.captureVariableName() != null && !step.captureVariableName().isBlank()
                && step.captureJsonPath() != null && !step.captureJsonPath().isBlank();
    }

    /** Le corps de la reponse est-il necessaire a la capture ? (pas pour un en-tete). */
    static boolean needsBody(StepExecutionSpec step) {
        return configured(step) && !step.capturesFromHeader();
    }

    /**
     * Lit la valeur selon la source de l'etape. Capture HTML d'un element dont le nom ressemble a un
     * jeton (csrf, token...) : valeur marquee sensible.
     */
    static Captured extract(StepExecutionSpec step, String body, HttpHeaders headers, ObjectMapper objectMapper) {
        if (!configured(step)) return Captured.NONE;
        if (step.capturesFromHtml()) {
            HtmlExtractor.Match match = HtmlExtractor.find(body, step.captureJsonPath());
            return new Captured(match.value(), TestRunMasking.isSensitiveHtmlCapture(step.captureJsonPath(), match.elementName()));
        }
        String value = step.capturesFromHeader()
                ? ResponseHeaderExtractor.extract(headers, step.captureJsonPath())
                : JsonPathExtractor.extract(objectMapper, body, step.captureJsonPath());
        return new Captured(value, false);
    }

    /** Valeur trouvee : disponible sous ${nom} pour la suite de l'iteration de ce VU. */
    static void store(StepExecutionSpec step, Captured captured, VariableContext variables) {
        if (captured.value() != null) {
            variables.capture(step.captureVariableName().trim(), captured.value());
        }
    }
}
