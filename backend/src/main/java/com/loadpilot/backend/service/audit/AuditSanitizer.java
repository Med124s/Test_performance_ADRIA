package com.loadpilot.backend.service.audit;

import java.util.regex.Pattern;

/**
 * Dernier filet de securite avant l'ecriture d'un texte dans le journal
 * d'audit : masque tout ce qui ressemble a un secret (mot de passe, jeton,
 * cle, en-tete Authorization, JWT) au cas ou un appelant en laisserait passer
 * un dans une description, un nom ou une valeur avant / apres.
 */
public final class AuditSanitizer {

    static final String MASK = "•••";

    /** « password=xxx », « mot de passe : xxx », « token: xxx », « secret=xxx »... */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|mot de passe|secret|client_secret|token|access_token|refresh_token|api[_-]?key|authorization)(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)");
    /** « Authorization: Basic xxx » / « Authorization=Bearer xxx » : toute la valeur. */
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)\\b(authorization)(\\s*[:=]\\s*)(?:(?:basic|bearer|digest|negotiate)\\s+)?\\S+");
    /** « Bearer eyJ... » */
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
    /** Jeton JWT (trois segments base64url commencant par eyJ). */
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+");
    /** Nom d'exception Java technique en fin de message (« : IllegalStateException »). */
    private static final Pattern EXCEPTION_NAME = Pattern.compile("\\s*:?\\s*\\b(?:[a-z]+\\.)*[A-Z][A-Za-z]*(?:Exception|Error)\\b");

    private AuditSanitizer() {
    }

    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = JWT.matcher(text).replaceAll(MASK);
        cleaned = AUTHORIZATION.matcher(cleaned).replaceAll("$1$2" + MASK);
        cleaned = BEARER.matcher(cleaned).replaceAll("Bearer " + MASK);
        cleaned = KEY_VALUE.matcher(cleaned).replaceAll("$1$2" + MASK);
        return cleaned;
    }

    /** Retire les noms d'exception techniques (anciennes descriptions) pour l'affichage. */
    public static String withoutExceptionNames(String text) {
        return text == null ? null : EXCEPTION_NAME.matcher(text).replaceAll("").trim();
    }
}
