package com.loadpilot.backend.enums;

/**
 * Les 3 seuls roles applicatifs reels (provisionnes dans Keycloak en Phase
 * 16.5 : ROLE_SUPER_ADMIN/ROLE_PERFORMANCE_ENGINEER/ROLE_VIEWER). Utilise
 * UNIQUEMENT pour valider server-side la valeur envoyee par un client lors
 * d'un changement de role (voir UserService.updateRole) - jamais pour
 * accepter un nom de role arbitraire, ce qui permettrait d'assigner un role
 * Keycloak inexistant ou non intentionnel.
 */
public enum AppRole {
    SUPER_ADMIN,
    PERFORMANCE_ENGINEER,
    VIEWER;

    /** Nom du role realm Keycloak reel correspondant (prefixe "ROLE_", voir
     * JwtAuthConverter et setup-realm.sh). */
    public String toKeycloakRoleName() {
        return "ROLE_" + name();
    }

    /** Libelle francais affiche (journal d'audit, exports) - jamais le code brut. */
    public String label() {
        return switch (this) {
            case SUPER_ADMIN -> "Administrateur";
            case PERFORMANCE_ENGINEER -> "Ingénieur performance";
            case VIEWER -> "Lecteur";
        };
    }

    /** Libelle d'un nom de role (ex. "SUPER_ADMIN"), "Aucun rôle" si null ou inconnu. */
    public static String labelOf(String roleName) {
        if (roleName == null) {
            return "Aucun rôle";
        }
        for (AppRole role : values()) {
            if (role.name().equals(roleName) || role.toKeycloakRoleName().equals(roleName)) {
                return role.label();
            }
        }
        return "Aucun rôle";
    }
}
