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

/**
 * Format d'erreur JSON unique pour l'API (voir ErrorResponse), coherent avec
 * les gestionnaires 401/403 deja en place depuis la Phase 4
 * (RestAuthenticationEntryPoint / RestAccessDeniedHandler).
 *
 * IMPORTANT : ce handler ne doit JAMAIS intercepter AccessDeniedException ni
 * AuthenticationException - ces deux-la restent geres par la chaine de
 * securite (ExceptionTranslationFilter -> RestAccessDeniedHandler /
 * RestAuthenticationEntryPoint, deja testes en Phase 4/5). Un handler
 * generique @ExceptionHandler(Exception.class) les intercepterait AVANT
 * qu'elles n'atteignent la chaine de filtres, cassant le 401/403 existant -
 * volontairement absent ici pour cette raison (voir aussi le commentaire sur
 * handleUnexpected ci-dessous, qui ne capture donc PAS ces deux types).
 */
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

    /** P0-B : refus deterministe d'une nouvelle Execution pour depassement
     * d'une limite de capacite configuree (jamais un blocage silencieux). */
    @ExceptionHandler(ExecutionLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleExecutionLimitExceeded(ExecutionLimitExceededException ex, HttpServletRequest request) {
        log.warn("Execution refusee (limite de capacite) sur {} : {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
    }

    /** P0-B : parametre de charge invalide pour ce deploiement (limite
     * configurable, distincte des bornes absolues @Min/@Max). */
    @ExceptionHandler(LoadConfigurationException.class)
    public ResponseEntity<ErrorResponse> handleLoadConfiguration(LoadConfigurationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** Phase 25 : l'Admin API Keycloak (service tiers du point de vue de ce
     * backend) est injoignable ou renvoie une erreur - jamais un 500 brut. */
    /** P1-B : configuration invalide d'une ScheduledExecution (voir sa Javadoc). */
    @ExceptionHandler(InvalidScheduleException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSchedule(InvalidScheduleException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** P1-D : valeur invalide pour une preference de profil (voir sa Javadoc). */
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

    /**
     * Filet de securite generique (Phase 8) : toute violation de contrainte
     * d'integrite non anticipee explicitement par un ConflictException
     * dedie (ex: contrainte FK/unique en base) devient un 409 propre plutot
     * que de remonter en 500 avec le message SQL brut.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Contrainte d'integrite violee sur {} : {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, "Cette operation viole une contrainte d'integrite (relation existante).", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status, message, request.getRequestURI()));
    }
}
