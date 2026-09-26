package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * P1-D — PATCH /api/profile. Volontairement UN SEUL champ modifiable :
 * "timezone" est la seule preference reellement possedee par l'utilisateur
 * (jamais username/name/email, qui restent la propriete de Keycloak et
 * seraient silencieusement ecrases au prochain login par
 * AppUserSyncService.sync - voir ProfileServiceImpl).
 */
public record ProfileUpdateRequest(
        @NotBlank(message = "Le fuseau horaire est obligatoire (identifiant IANA, ex: Africa/Casablanca).")
        String timezone
) {
}
