package com.loadpilot.backend.service.report;

/**
 * P1-A (prompt section 25) — echappement CSV partage pour l'export de
 * rapport. Deux protections distinctes, TOUTES DEUX necessaires :
 *
 * 1) RFC 4180 (deja applique par AuditLogServiceImpl.csvField, reproduit
 *    ici a l'identique pour ce nouvel export) : guillemets doubles autour
 *    de toute valeur contenant une virgule/un guillemet/un retour a la
 *    ligne, guillemet interne double.
 *
 * 2) CSV/Formula injection (NON couvert par l'export audit existant -
 *    observation notee dans le rapport P1-A, non corrigee hors perimetre) :
 *    une valeur commencant par '=', '+', '-' ou '@' peut etre interpretee
 *    comme une formule par Excel/LibreOffice/Google Sheets a l'ouverture du
 *    fichier - neutralisee ici en prefixant d'une apostrophe, technique
 *    standard (OWASP CSV Injection) qui force une interpretation en texte
 *    brut sans alterer la valeur affichee pour l'utilisateur.
 */
public final class CsvUtils {

    private CsvUtils() {
    }

    public static String field(Object value) {
        if (value == null) {
            return "";
        }
        String raw = value.toString();
        if (!raw.isEmpty() && "=+-@".indexOf(raw.charAt(0)) >= 0) {
            raw = "'" + raw;
        }
        boolean needsQuoting = raw.contains(",") || raw.contains("\"") || raw.contains("\n") || raw.contains("\r");
        String escaped = raw.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }
}
