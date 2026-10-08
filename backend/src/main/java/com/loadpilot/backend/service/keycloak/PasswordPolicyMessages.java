package com.loadpilot.backend.service.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduit en francais le refus de Keycloak quand un mot de passe ne respecte
 * pas la politique du realm (reponse 400 de POST /users ou PUT
 * /users/{id}/reset-password, ex. {"error":"invalidPasswordMinLengthMessage",
 * "error_description":"Invalid password: minimum length 8."}). Le mot de passe
 * lui-meme n'est jamais present dans la reponse ni dans le message produit.
 */
public final class PasswordPolicyMessages {

    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    static final String GENERIC = "Le mot de passe ne respecte pas la politique de sécurité.";

    private PasswordPolicyMessages() {
    }

    /** Message francais, ou null si la reponse n'est pas un refus de politique de mot de passe. */
    public static String fromKeycloakError(String responseBody, ObjectMapper objectMapper) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        String code;
        String description;
        try {
            JsonNode json = objectMapper.readTree(responseBody);
            code = json.path("error").asText(json.path("errorMessage").asText(""));
            description = json.path("error_description").asText("");
        } catch (Exception e) {
            return null;
        }
        if (!code.startsWith("invalidPassword")) {
            return null;
        }
        Matcher m = NUMBER.matcher(description);
        int n = m.find() ? Integer.parseInt(m.group(1)) : 1;
        return switch (code) {
            case "invalidPasswordMinLengthMessage" -> "Le mot de passe doit contenir au moins " + n + " caractères.";
            case "invalidPasswordMaxLengthMessage" -> "Le mot de passe doit contenir au plus " + n + " caractères.";
            case "invalidPasswordMinUpperCaseCharsMessage" -> "Le mot de passe doit contenir au moins " + n + (n > 1 ? " majuscules." : " majuscule.");
            case "invalidPasswordMinLowerCaseCharsMessage" -> "Le mot de passe doit contenir au moins " + n + (n > 1 ? " minuscules." : " minuscule.");
            case "invalidPasswordMinDigitsMessage" -> "Le mot de passe doit contenir au moins " + n + (n > 1 ? " chiffres." : " chiffre.");
            case "invalidPasswordMinSpecialCharsMessage" -> "Le mot de passe doit contenir au moins " + n + (n > 1 ? " caractères spéciaux." : " caractère spécial.");
            case "invalidPasswordNotUsernameMessage" -> "Le mot de passe ne doit pas être identique au nom d'utilisateur.";
            case "invalidPasswordNotEmailMessage" -> "Le mot de passe ne doit pas être identique à l'adresse e-mail.";
            case "invalidPasswordHistoryMessage" -> "Ce mot de passe a déjà été utilisé récemment : choisissez-en un autre.";
            case "invalidPasswordBlacklistedMessage" -> "Ce mot de passe est trop courant : choisissez-en un autre.";
            default -> GENERIC;
        };
    }
}
