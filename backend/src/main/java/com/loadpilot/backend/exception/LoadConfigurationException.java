package com.loadpilot.backend.exception;

/**
 * P0-B — le Scenario demande un parametre de charge invalide pour CE
 * deploiement (ex : virtualUsers superieur a la limite configurable par
 * environnement, voir app.execution.max-virtual-users-per-execution) -
 * distinct des bornes absolues de ScenarioRequest (@Min/@Max, qui protegent
 * contre des valeurs absurdes quel que soit le deploiement). Mappee en 400
 * (Bad Request) par GlobalExceptionHandler : c'est une erreur de
 * configuration du client, pas un conflit d'etat serveur.
 */
public class LoadConfigurationException extends RuntimeException {

    public LoadConfigurationException(String message) {
        super(message);
    }
}
