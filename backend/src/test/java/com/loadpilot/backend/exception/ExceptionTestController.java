package com.loadpilot.backend.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controleur reserve aux tests de GlobalExceptionHandler (Phase 14) - existe
 * UNIQUEMENT sous src/test/java, jamais empaquete dans l'application reelle
 * (meme convention que security.SecurityTestController, Phase 4). Chaque
 * endpoint declenche volontairement un type d'exception different pour
 * verifier le mapping HTTP/JSON reel de GlobalExceptionHandler, sans jamais
 * dupliquer sa logique.
 */
@Validated
@RestController
class ExceptionTestController {

    @GetMapping("/api/test/exceptions/not-found")
    public void notFound() {
        throw new ResourceNotFoundException("Ressource de test introuvable.");
    }

    @GetMapping("/api/test/exceptions/conflict")
    public void conflict() {
        throw new ConflictException("Conflit de test.");
    }

    @GetMapping("/api/test/exceptions/data-integrity")
    public void dataIntegrity() {
        throw new DataIntegrityViolationException("Violation de contrainte simulee.");
    }

    @GetMapping("/api/test/exceptions/unexpected")
    public void unexpected() {
        throw new IllegalStateException("Erreur technique inattendue de test.");
    }

    @PostMapping("/api/test/exceptions/validate-body")
    public void validateBody(@Valid @RequestBody ValidatedBody body) {
    }

    @GetMapping("/api/test/exceptions/validate-param")
    public void validateParam(@RequestParam @Positive int value) {
    }

    @GetMapping("/api/test/exceptions/type-mismatch/{id}")
    public void typeMismatch(@PathVariable UUID id) {
    }

    public record ValidatedBody(@NotBlank(message = "Le champ 'name' est obligatoire.") String name) {
    }
}
