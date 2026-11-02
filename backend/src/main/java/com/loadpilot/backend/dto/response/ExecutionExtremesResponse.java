package com.loadpilot.backend.dto.response;

import java.util.List;

/** Requetes les plus rapides (MIN) ou les plus lentes (MAX) d'une execution, avec leur contexte dans le test. */
public record ExecutionExtremesResponse(
        String kind,
        /** Valeur de la carte (meme calcul que le rapport) ; null si aucune requete. */
        Long cardValueMs,
        /** Nombre de requetes mesurees de l'execution. */
        long measuredRequests,
        int intervalSeconds,
        List<ExtremeRequest> requests
) {
    public record ExtremeRequest(
            ExecutionRequestRowResponse request,
            /** Secondes depuis le debut du test (fin de la requete, comme l'analyse dans le temps). */
            double secondsFromStart,
            /** Debut de l'intervalle de l'analyse dans le temps contenant cette requete (s). */
            int intervalStartSeconds,
            Integer activeVirtualUsers,
            Double throughput,
            Long p95Ms
    ) {
    }
}
