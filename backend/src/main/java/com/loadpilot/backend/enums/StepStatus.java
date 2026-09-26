package com.loadpilot.backend.enums;

/**
 * Statut de CYCLE DE VIE/CONFIGURATION d'un Step (active/desactive) - PAS un
 * resultat d'execution. Meme logique que ScenarioStatus (Phase 7) : aucune
 * Execution n'existe encore a ce stade du projet (Phase 9), donc ce statut
 * ne dit jamais "cette etape a reussi/echoue" - il dit seulement "cette
 * etape est activee et sera jouee lors d'une future execution du scenario".
 *
 * Non nullable, ACTIVE par defaut a la creation : comme pour Scenario, c'est
 * un choix declaratif de l'utilisateur, vrai des la creation - pas une
 * pretention de resultat.
 */
public enum StepStatus {
    ACTIVE,
    INACTIVE
}
