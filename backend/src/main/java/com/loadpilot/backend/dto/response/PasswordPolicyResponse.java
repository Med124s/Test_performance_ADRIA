package com.loadpilot.backend.dto.response;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Politique de mot de passe du realm (GET /api/users/password-policy), pour la
 * validation en direct dans le formulaire. Keycloak reste seul juge : il
 * re-verifie chaque mot de passe a l'enregistrement.
 * 0 = pas d'exigence ; maxLength 0 = pas de maximum.
 */
public record PasswordPolicyResponse(
        int minLength,
        int maxLength,
        int minUpperCase,
        int minLowerCase,
        int minDigits,
        int minSpecialChars,
        boolean notUsername,
        boolean notEmail
) {

    private static final Pattern RULE = Pattern.compile("(\\w+)\\(([^)]*)\\)");

    /** Lit le format Keycloak, ex. "length(8) and digits(1) and notUsername(undefined)". */
    public static PasswordPolicyResponse parse(String policy) {
        int minLength = 0, maxLength = 0, upper = 0, lower = 0, digits = 0, special = 0;
        boolean notUsername = false, notEmail = false;
        Matcher m = RULE.matcher(policy == null ? "" : policy);
        while (m.find()) {
            String arg = m.group(2).trim();
            switch (m.group(1)) {
                case "length" -> minLength = number(arg, 8);
                case "maxLength" -> maxLength = number(arg, 64);
                case "upperCase" -> upper = number(arg, 1);
                case "lowerCase" -> lower = number(arg, 1);
                case "digits" -> digits = number(arg, 1);
                case "specialChars" -> special = number(arg, 1);
                case "notUsername" -> notUsername = true;
                case "notEmail" -> notEmail = true;
                default -> { /* autres regles (historique, liste noire...) : verifiees par Keycloak seulement */ }
            }
        }
        return new PasswordPolicyResponse(minLength, maxLength, upper, lower, digits, special, notUsername, notEmail);
    }

    /** Valeur par defaut de Keycloak quand la regle est ecrite sans nombre. */
    private static int number(String arg, int keycloakDefault) {
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            return keycloakDefault;
        }
    }
}
