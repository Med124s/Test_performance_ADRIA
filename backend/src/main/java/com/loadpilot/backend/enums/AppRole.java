package com.loadpilot.backend.enums;

/** Roles applicatifs. */
public enum AppRole {
    SUPER_ADMIN,
    PERFORMANCE_ENGINEER,
    VIEWER;

    /** Nom du role dans Keycloak (prefixe "ROLE_"). */
    public String toKeycloakRoleName() {
        return "ROLE_" + name();
    }

    /** Libelle francais du role. */
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
