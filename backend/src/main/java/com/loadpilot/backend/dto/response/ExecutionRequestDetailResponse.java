package com.loadpilot.backend.dto.response;

import java.util.Map;

/**
 * Detail complet d'une requete d'execution : requete envoyee (URL finale avec les variables
 * remplacees), reponse recue (null si aucune reponse : delai depasse, erreur reseau, variable
 * introuvable), verifications. Secrets toujours masques ; corps limites a 10 Ko (« tronque »).
 */
public record ExecutionRequestDetailResponse(
        ExecutionRequestRowResponse summary,
        String kind,
        Request request,
        Response response,
        Checks checks
) {
    public record Request(String method, String url, Map<String, String> headers, String body, boolean bodyTruncated) {
    }

    public record Response(Integer status, Long timeMs, Long sizeBytes, Map<String, String> headers, String body,
            boolean bodyTruncated) {
    }

    public record Checks(String expectedStatus, Boolean statusOk, String assertionText, Boolean assertionOk,
            String captureName, String capturePath, String captureValue, Boolean captureFound) {
    }
}
