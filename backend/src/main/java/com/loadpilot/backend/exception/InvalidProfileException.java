package com.loadpilot.backend.exception;

/**
 * P1-D — valeur invalide fournie pour une preference de profil (ex: fuseau
 * horaire qui n'est pas un identifiant IANA reconnu par ZoneId.of()) - voir
 * ProfileServiceImpl. Mappee en 400 par GlobalExceptionHandler.
 */
public class InvalidProfileException extends RuntimeException {

    public InvalidProfileException(String message) {
        super(message);
    }
}
