package com.loadpilot.backend.exception;

/**
 * P1-B — configuration invalide d'une ScheduledExecution (expression cron
 * illisible, fuseau horaire inconnu, "runAt" absent/deja passe pour un
 * ONE_TIME...) - distincte de ConstraintViolationException/@Valid car il
 * s'agit de validations CROISEES entre plusieurs champs (voir
 * ScheduledExecutionServiceImpl), jamais exprimables proprement avec les
 * seules annotations Bean Validation. Mappee en 400 par GlobalExceptionHandler.
 */
public class InvalidScheduleException extends RuntimeException {

    public InvalidScheduleException(String message) {
        super(message);
    }
}
