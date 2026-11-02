package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;

/** Raison courte d'un statut, affichee sous le badge : "Erreurs : 2,5 %", "P95 > seuil", "Annulee par admin-test"... */
public final class ExecutionStatusReason {

    /** Debut du message d'arret par le garde-fou memoire. */
    public static final String MEMORY_LIMIT_PREFIX = "Arrêt automatique : trop de résultats en mémoire";

    private ExecutionStatusReason() {
    }

    public static String of(Execution e) {
        if (e.getStatus() == null) return null;
        String message = e.getErrorMessage() == null ? "" : e.getErrorMessage();
        long ok = e.getSuccessfulSteps() == null ? 0 : e.getSuccessfulSteps();
        long ko = e.getFailedSteps() == null ? 0 : e.getFailedSteps();
        double errorRate = ExecutionVerdict.roundedRate(ko, ok + ko);
        return switch (e.getStatus()) {
            case CANCELLED -> {
                if (e.getCancelledBy() != null) yield "Annulée par " + e.getCancelledBy();
                if (message.startsWith(MEMORY_LIMIT_PREFIX)) {
                    yield "Arrêt automatique : limite mémoire";
                }
                if (message.startsWith(ErrorRateBrake.REASON_PREFIX)) yield "Arrêt automatique : trop d’erreurs";
                if (message.toLowerCase().contains("pause")) yield "Annulée : pause trop longue";
                yield "Annulée";
            }
            case THRESHOLDS_EXCEEDED -> e.getMaxErrorRatePercent() != null && errorRate > e.getMaxErrorRatePercent()
                    ? "Erreurs : " + ExecutionVerdict.format(errorRate) + " %"
                    : "P95 > seuil";
            case FAILED -> failedReason(e, message, ko, errorRate);
            default -> null;
        };
    }

    private static String failedReason(Execution e, String message, long failures, double errorRate) {
        if (message.startsWith("Authentification")) return "Authentification échouée";
        if (message.startsWith(ExecutionVerdict.UNREACHABLE)) return "Application injoignable";
        String lower = message.toLowerCase();
        if (lower.contains("restarted") || lower.contains("redémarr") || lower.contains("redemarr")) return "Interrompue (redémarrage du serveur)";
        if (lower.startsWith("aucune requ")) return "Aucune requête exécutée";
        // Execution sans seuils : echouee des la premiere requete en erreur.
        if (e.getMaxErrorRatePercent() == null && failures > 0) return "Erreurs : " + ExecutionVerdict.format(errorRate) + " %";
        return "Erreur interne";
    }
}
