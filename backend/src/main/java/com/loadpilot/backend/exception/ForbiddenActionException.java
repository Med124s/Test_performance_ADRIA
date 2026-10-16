package com.loadpilot.backend.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * 403 avec un message precis pour l'utilisateur (ex. « Seul un Super administrateur peut modifier
 * son nom et son e-mail. »). Traite comme tout refus d'acces par la chaine de securite
 * (RestAccessDeniedHandler), qui renvoie alors ce message au lieu du message generique.
 */
public class ForbiddenActionException extends AccessDeniedException {

    public ForbiddenActionException(String message) {
        super(message);
    }
}
