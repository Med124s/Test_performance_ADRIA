package com.loadpilot.backend.exception;

/**
 * Requete refusee (400) pour une regle metier lisible par l'utilisateur : brouillon non
 * executable/planifiable, scenario incomplet a la publication, CSV ou etape invalide.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
