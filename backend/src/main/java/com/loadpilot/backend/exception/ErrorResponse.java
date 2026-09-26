package com.loadpilot.backend.exception;

import java.time.Instant;
import org.springframework.http.HttpStatus;

/**
 * Format JSON unique pour toute erreur renvoyee par l'API - utilise des la
 * Phase 4 par les gestionnaires 401/403 (voir security/), et reutilise tel
 * quel par le futur GlobalExceptionHandler (Phase 6+) pour rester coherent
 * partout : {timestamp, status, error, message, path}.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {

    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message, path);
    }
}
