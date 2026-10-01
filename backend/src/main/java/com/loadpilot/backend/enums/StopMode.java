package com.loadpilot.backend.enums;

/**
 * Mode d'arret REEL d'une charge (Scenario, copie sur l'Execution au
 * lancement - voir ExecutionTransactionHelper) :
 *
 * AUTO   : comportement historique inchange - l'execution s'arrete d'elle
 *          meme a durationSeconds/iterations (ou apres une seule passe si
 *          aucun des deux n'est configure).
 * MANUAL : durationSeconds/iterations sont IGNORES - l'execution tourne
 *          jusqu'a une annulation explicite (voir RunningExecutionHandle/
 *          POST /api/executions/{id}/cancel, deja reel). Aucune limite
 *          inventee : c'est un vrai mode, pas une simulation.
 *
 * Non nullable, AUTO par defaut (voir 015-add-real-product-fields.xml) -
 * aucune regression sur les Scenario/Execution existants.
 */
public enum StopMode {
    AUTO,
    MANUAL
}
