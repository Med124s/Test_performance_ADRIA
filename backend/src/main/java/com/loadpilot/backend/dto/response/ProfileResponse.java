package com.loadpilot.backend.dto.response;

import java.util.Set;

/** Profil de l'utilisateur connecte ; "id" est le sujet Keycloak. */
public record ProfileResponse(
        String id,
        String username,
        String name,
        String email,
        Set<String> roles,
        /** Fuseau horaire choisi ; null si jamais choisi. */
        String timezone,
        /** Role applicatif (SUPER_ADMIN, PERFORMANCE_ENGINEER, VIEWER) ; null si aucun. Jamais les roles techniques. */
        String appRole,
        /** Libelle francais du role applicatif : « Super administrateur », « Ingénieur performance », « Observateur ». */
        String appRoleLabel,
        /** Apparence choisie : LIGHT, DARK ou SYSTEM ; null = jamais choisie. */
        String theme,
        /** Prenom et nom (systeme d'authentification) : pre-remplissent « Modifier ». */
        String firstName,
        String lastName,
        /** true pour le Super administrateur seulement : nom, prenom et e-mail modifiables. */
        boolean canEditIdentity
) {
}
