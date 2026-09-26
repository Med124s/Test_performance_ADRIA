package com.loadpilot.backend.exception;

/**
 * P0-B — refus deterministe et explicite d'une nouvelle Execution parce que
 * le serveur LoadPilot a deja atteint une limite de capacite CONFIGUREE
 * (utilisateurs virtuels globaux ou nombre d'executions simultanees, voir
 * RunningExecutionRegistry) - jamais un blocage silencieux : l'Execution
 * n'est JAMAIS creee en base quand cette exception est levee (voir
 * ExecutionServiceImpl.execute, verification AVANT toute ecriture).
 * Mappee en 429 (Too Many Requests) par GlobalExceptionHandler.
 */
public class ExecutionLimitExceededException extends RuntimeException {

    public ExecutionLimitExceededException(String message) {
        super(message);
    }
}
