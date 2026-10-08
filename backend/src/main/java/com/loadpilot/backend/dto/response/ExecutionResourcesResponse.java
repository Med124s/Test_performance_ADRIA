package com.loadpilot.backend.dto.response;

import java.util.List;

/**
 * CPU et RAM du PROCESSUS de l'application testee (pas de la machine, pas de LoadPilot), pour le
 * suivi en direct. CPU en % d'un coeur (100 % = un coeur entierement occupe), RAM en Mo.
 * Pendant l'execution : derniere mesure et dernieres valeurs (mini-courbes) ; une fois terminee :
 * resume enregistre seulement. message : monitoring indisponible (l'execution continue).
 */
public record ExecutionResourcesResponse(
        String source,
        int intervalSeconds,
        Double cpuPercent,
        Double memoryMb,
        Double cpuAvgPercent,
        Double cpuMaxPercent,
        Double memoryMaxMb,
        String message,
        List<Double> cpuHistory,
        List<Double> memoryHistory
) {
}
