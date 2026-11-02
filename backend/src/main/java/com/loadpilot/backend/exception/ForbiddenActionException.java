package com.loadpilot.backend.exception;

import org.springframework.security.access.AccessDeniedException;

/** Refus d'acces (403) avec un message precis pour l'utilisateur. */
public class ForbiddenActionException extends AccessDeniedException {

    public ForbiddenActionException(String message) {
        super(message);
    }
}
