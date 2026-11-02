package com.loadpilot.backend.service.keycloak;

/** Compte a creer dans Keycloak ; "password" null = l'utilisateur le choisira via un lien e-mail. */
public record NewKeycloakUser(
        String username,
        String email,
        String firstName,
        String lastName,
        String password,
        boolean temporaryPassword
) {

    /** Compte sans mot de passe initial. */
    public NewKeycloakUser(String username, String email, String firstName, String lastName) {
        this(username, email, firstName, lastName, null, false);
    }

    @Override
    public String toString() {
        return "NewKeycloakUser[username=" + username + ", email=" + email + ", firstName=" + firstName
                + ", lastName=" + lastName + ", password=" + (password == null ? "null" : "***")
                + ", temporaryPassword=" + temporaryPassword + "]";
    }
}
