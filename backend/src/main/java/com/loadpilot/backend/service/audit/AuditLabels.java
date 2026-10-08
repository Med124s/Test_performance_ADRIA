package com.loadpilot.backend.service.audit;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;

/**
 * Libelles francais du journal d'audit (page, panneau de detail, export CSV) :
 * jamais de code brut (CREATE, SUPER_ADMIN...) affiche a l'utilisateur.
 */
public final class AuditLabels {

    private AuditLabels() {
    }

    public static String action(AuditAction action, String legacyDescription) {
        if (action == null) {
            return "Action";
        }
        return switch (action) {
            case CREATE -> "Création";
            case READ -> "Consultation";
            case UPDATE -> "Modification";
            case DELETE -> "Suppression";
            case TEST -> "Test";
            case EXECUTE -> "Lancement";
            case FINISH -> "Fin";
            case RETRY -> "Relance";
            case CANCEL -> "Annulation";
            case LOGIN -> "Connexion";
            case LOGOUT -> "Déconnexion";
            case ENABLE -> "Activation";
            case DISABLE -> "Désactivation";
            case TRIGGER -> "Déclenchement";
            case VIEW_HISTORY -> "Consultation de l’historique";
            case VIEW_REPORT -> "Consultation du rapport";
            // Ancien code pose a tort sur les consultations : seules les lignes « Report viewed... » en sont.
            case EXPORT_REPORT -> legacyDescription != null && legacyDescription.startsWith("Report view")
                    ? "Consultation du rapport" : "Export du rapport";
            case EXPORT_CSV -> "Export CSV du rapport";
            case ARCHIVE -> "Archivage";
            case RESTORE -> "Restauration";
            case PAUSE -> "Mise en pause";
            case RESUME -> "Reprise";
            case LOGIN_FAILED -> "Échec de connexion";
            case ACCOUNT_LOCKED -> "Compte bloqué temporairement";
            case PASSWORD_SET -> "Mot de passe défini";
            case PASSWORD_RESET_LINK -> "Lien de réinitialisation envoyé";
            case PASSWORD_CHANGED -> "Mot de passe changé";
            case ROLE_CHANGE -> "Changement de rôle";
            case PURGE -> "Purge du journal";
        };
    }

    public static String module(AuditModule module) {
        if (module == null) {
            return "—";
        }
        return switch (module) {
            case AUTH -> "Authentification";
            case USER -> "Utilisateurs";
            case APPLICATION -> "Applications";
            case SCENARIO -> "Scénarios";
            case STEP -> "Étapes";
            case EXECUTION -> "Exécutions";
            case METRIC -> "Métriques";
            case DASHBOARD -> "Tableau de bord";
            case ADMIN -> "Administration";
            case SCHEDULING -> "Planification";
            case SECURITY -> "Sécurité";
            case SETTINGS -> "Paramètres";
        };
    }

    public static String result(AuditResult result) {
        return result == AuditResult.FAILURE ? "Échec" : "Réussi";
    }

    /** Objet tel qu'il apparait dans la phrase : « de l’application Banking », « du scénario X »... */
    static String object(AuditTargetType type, String label, AuditAction action) {
        if (type == null || label == null || label.isBlank()) {
            return null;
        }
        return switch (type) {
            case APPLICATION -> "de l’application « " + label + " »";
            case SCENARIO -> "du scénario « " + label + " »";
            case EXECUTION -> "de l’exécution « " + label + " »";
            case SCHEDULE -> "de la planification « " + label + " »";
            case USER -> switch (action) {
                case PASSWORD_SET -> "pour le compte « " + label + " »";
                case PASSWORD_RESET_LINK -> "au compte « " + label + " »";
                default -> "du compte « " + label + " »";
            };
            case SETTINGS -> "« " + label + " »";
        };
    }

    /**
     * Phrase lisible d'une ligne, ex. « Création de l’application « Banking » »,
     * « Lancement de l’exécution « Parcours bancaire du 07/10 à 14:32 » ».
     * Sans objet connu (anciennes lignes) : l'action et le module.
     */
    public static String summary(AuditAction action, AuditModule module, AuditTargetType targetType, String targetLabel, String legacyDescription) {
        String verb = action(action, legacyDescription);
        if (action == AuditAction.EXPORT_CSV && targetType == AuditTargetType.SETTINGS && targetLabel != null) {
            return "Export CSV du " + targetLabel.substring(0, 1).toLowerCase() + targetLabel.substring(1);
        }
        String object = object(targetType, targetLabel, action);
        if (object != null) {
            return verb + " " + object;
        }
        if (action == AuditAction.LOGIN || action == AuditAction.LOGOUT || action == AuditAction.LOGIN_FAILED
                || action == AuditAction.ACCOUNT_LOCKED || action == AuditAction.PASSWORD_CHANGED
                || action == AuditAction.PURGE || action == AuditAction.VIEW_HISTORY) {
            return verb;
        }
        return verb + " · " + module(module);
    }

    /** « localhost » pour l'adresse de la machine elle-meme, sinon l'IP telle quelle. */
    public static String ip(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String trimmed = ip.trim();
        return switch (trimmed) {
            case "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" -> "localhost";
            default -> trimmed;
        };
    }
}
