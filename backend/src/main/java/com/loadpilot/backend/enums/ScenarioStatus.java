package com.loadpilot.backend.enums;

/**
 * Statut de CYCLE DE VIE d'un scenario (active/desactive) - PAS un resultat
 * d'execution. Aucune Execution n'existe encore a ce stade du projet (voir
 * Phase 9) : ce statut ne dit jamais "ce scenario a reussi/echoue", il dit
 * seulement "ce scenario est active et disponible pour etre lance plus
 * tard". Voir aussi ApplicationStatus (Phase 6), qui lui concerne un
 * resultat de test reel - deux notions volontairement distinctes.
 *
 * Non nullable : contrairement au statut d'Application (qui attend un vrai
 * test HTTP avant d'avoir une valeur), l'activation d'un scenario est un
 * choix declaratif de l'utilisateur, vrai des sa creation (ACTIVE par
 * defaut) - ce n'est pas "pretendre" un resultat qui n'a pas eu lieu.
 */
public enum ScenarioStatus {
    ACTIVE,
    INACTIVE
}
