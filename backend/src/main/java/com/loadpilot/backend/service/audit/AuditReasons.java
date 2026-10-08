package com.loadpilot.backend.service.audit;

import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ExecutionLimitExceededException;
import com.loadpilot.backend.exception.InvalidProfileException;
import com.loadpilot.backend.exception.InvalidScheduleException;
import com.loadpilot.backend.exception.KeycloakAdminException;
import com.loadpilot.backend.exception.LoadConfigurationException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Raison d'un echec, en francais et sans nom de classe technique : les
 * exceptions metier de LoadPilot portent deja un message francais destine a
 * l'utilisateur ; toute autre erreur devient « Erreur inattendue ».
 */
public final class AuditReasons {

    private AuditReasons() {
    }

    public static String of(Throwable e) {
        if (e == null) {
            return "Erreur inattendue.";
        }
        if (e instanceof ResourceNotFoundException) {
            return "Élément introuvable (supprimé entre-temps ?).";
        }
        if (e instanceof AccessDeniedException) {
            return "Action non autorisée pour ce rôle.";
        }
        if (e instanceof ConflictException || e instanceof BadRequestException || e instanceof LoadConfigurationException
                || e instanceof InvalidScheduleException || e instanceof ExecutionLimitExceededException
                || e instanceof InvalidProfileException || e instanceof KeycloakAdminException) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? "Opération refusée." : AuditSanitizer.withoutExceptionNames(message);
        }
        return "Erreur inattendue.";
    }
}
