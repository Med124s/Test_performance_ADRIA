package com.loadpilot.backend.dto.response;

import java.time.Instant;

/**
 * Etat de conservation du detail d'une execution (rapport, detail, liste des executions).
 * keep : option « Conserver » (jamais purgee) ; resultsPurgedAt : detail des requetes supprime
 * (le rapport vient alors du rapport fige) ; requestDetailsPurgedAt : en-tetes et corps supprimes ;
 * notice : phrase a afficher (null si rien n'a ete supprime).
 */
public record ResultsRetentionResponse(
        boolean keep,
        Instant resultsPurgedAt,
        Instant requestDetailsPurgedAt,
        boolean fromSnapshot,
        String notice
) {
}
