package com.loadpilot.backend.service.execution;

import java.util.Map;

/**
 * Detail complet d'UNE requete d'une execution, tel qu'il sera enregistre : secrets deja masques,
 * corps deja limites (voir RequestDetailRecorder.BODY_LIMIT_BYTES). "response*" null si aucune
 * reponse n'a ete recue (delai depasse, erreur reseau, variable introuvable avant l'envoi).
 */
public record RequestDetail(
        Kind kind,
        String method,
        String url,
        Map<String, String> requestHeaders,
        String requestBody,
        boolean requestBodyTruncated,
        Integer responseStatus,
        Map<String, String> responseHeaders,
        String responseBody,
        boolean responseBodyTruncated,
        Long responseSizeBytes,
        Checks checks
) {
    public enum Kind { FAILURE, SAMPLE }

    /** Verifications de l'etape : code attendu, texte attendu, variable capturee. */
    public record Checks(
            String expectedStatus,
            Boolean statusOk,
            String assertionText,
            Boolean assertionOk,
            String captureName,
            String capturePath,
            String captureValue,
            Boolean captureFound
    ) {
    }

    /** Taille approximative en memoire (octets), pour le plafond global par execution. */
    public long approxBytes() {
        long total = 256;
        total += len(method) + len(url) + len(requestBody) + len(responseBody);
        total += headersLength(requestHeaders) + headersLength(responseHeaders);
        if (checks != null) {
            total += len(checks.expectedStatus()) + len(checks.assertionText()) + len(checks.captureName())
                    + len(checks.capturePath()) + len(checks.captureValue());
        }
        return total * 2; // chaines Java : ~2 octets par caractere
    }

    private static long len(String s) {
        return s == null ? 0 : s.length();
    }

    private static long headersLength(Map<String, String> headers) {
        if (headers == null) return 0;
        long n = 0;
        for (Map.Entry<String, String> e : headers.entrySet()) n += len(e.getKey()) + len(e.getValue()) + 4;
        return n;
    }
}
