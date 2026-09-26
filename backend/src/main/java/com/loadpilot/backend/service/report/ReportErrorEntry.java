package com.loadpilot.backend.service.report;

import com.loadpilot.backend.enums.HttpMethod;
import java.time.Instant;

/**
 * UNE erreur reelle rencontree pendant l'Execution (voir prompt P1-A
 * section 21). Contient uniquement des informations deja publiques dans
 * ExecutionStepResult - JAMAIS de header, token, mot de passe ou secret
 * (aucun de ces champs n'existe sur ExecutionStepResult, donc aucun risque
 * de fuite ici : voir HttpClientExecutionEngine, qui ne capture jamais les
 * headers/corps de requete-reponse dans les resultats persistes).
 */
public record ReportErrorEntry(
        String stepName,
        HttpMethod method,
        String url,
        Integer httpStatus,
        String error,
        Instant timestamp
) {
}
