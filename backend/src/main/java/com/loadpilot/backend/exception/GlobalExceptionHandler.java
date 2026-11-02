package com.loadpilot.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Traduit les exceptions en reponses d'erreur JSON. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(ExecutionLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleExecutionLimitExceeded(ExecutionLimitExceededException ex, HttpServletRequest request) {
        log.warn("Execution refusee (limite de capacite) sur {} : {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
    }

    /** Lecture refusee avant de charger tous les resultats d'une execution (voir ResultLoadGuard) : 413 ou 409. */
    @ExceptionHandler(ResultSetTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleResultSetTooLarge(ResultSetTooLargeException ex, HttpServletRequest request) {
        return build(ex.status(), ex.getMessage(), request);
    }

    /** Manque de memoire pendant une requete : reponse 503. */
    @ExceptionHandler(OutOfMemoryError.class)
    public ResponseEntity<ErrorResponse> handleOutOfMemory(OutOfMemoryError error, HttpServletRequest request) {
        log.error("Memoire insuffisante pendant {} {} : requete abandonnee ({}). D'autres traitements en cours ont pu "
                + "echouer en meme temps ; augmenter LOADPILOT_JVM_MAX_MEMORY ou reduire le volume des tests.",
                request.getMethod(), request.getRequestURI(), error.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Mémoire du serveur insuffisante pour traiter cette demande : "
                + "elle a été abandonnée. Réessayez plus tard ; si le problème persiste, l'exécution est trop volumineuse "
                + "pour la mémoire de ce serveur.", request);
    }

    @ExceptionHandler(LoadConfigurationException.class)
    public ResponseEntity<ErrorResponse> handleLoadConfiguration(LoadConfigurationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** Regle metier lisible (brouillon non executable, scenario incomplet, CSV invalide...). */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** Admin API Keycloak injoignable ou en erreur. */
    @ExceptionHandler(InvalidScheduleException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSchedule(InvalidScheduleException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidProfileException.class)
    public ResponseEntity<ErrorResponse> handleInvalidProfile(InvalidProfileException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(KeycloakAdminException.class)
    public ResponseEntity<ErrorResponse> handleKeycloakAdmin(KeycloakAdminException ex, HttpServletRequest request) {
        log.warn("Appel Admin API Keycloak echoue sur {} : {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " : " + fe.getDefaultMessage())
                .collect(Collectors.joining(" ; "));
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Corps de requete JSON illisible ou malforme.", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Parametre '" + ex.getName() + "' invalide.", request);
    }

    /** Violation de contrainte en base : 409. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Contrainte d'integrite violee sur {} : {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT,
                "Opération impossible : cet élément est encore utilisé par d'autres données (scénarios, étapes ou exécutions).", request);
    }

    /** Violation de contrainte remontee directement par Hibernate : 409. */
    @ExceptionHandler(org.hibernate.exception.ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleHibernateConstraintViolation(org.hibernate.exception.ConstraintViolationException ex,
            HttpServletRequest request) {
        log.warn("Contrainte d'integrite violee (Hibernate) sur {} : {}", request.getRequestURI(), ex.getConstraintName());
        return build(HttpStatus.CONFLICT,
                "Opération impossible pour le moment : des données liées sont encore en cours d'enregistrement. Réessayez dans quelques secondes.",
                request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status, message, request.getRequestURI()));
    }
}
