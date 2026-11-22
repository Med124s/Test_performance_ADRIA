package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.StressOutcome;

/**
 * Resultat d'un test de stress, lu dans le handle a la fin du moteur. "outcome" est null si le test s'est arrete
 * autrement (annulation par l'utilisateur, limite memoire) ; "breakpoint" seulement pour BREAKPOINT, "limit" pour NO_BREAKPOINT.
 */
public record StressResult(StressOutcome outcome, BreakpointDetector.Breakpoint breakpoint, StressRamp.Limit limit,
        int peakVirtualUsers, double peakOfferedRps, String message) {

    /** Resultat de l'execution suivie par "handle" ; null hors mode stress. */
    public static StressResult of(RunningExecutionHandle handle) {
        StressRamp ramp = handle.stressRamp();
        if (!handle.isStressMode() && ramp == null) return null;
        int peakVirtualUsers = ramp != null ? ramp.peakVirtualUsers() : 0;
        double peakOfferedRps = ramp != null ? ramp.peakOfferedRps() : 0;
        return switch (handle.stressOutcome()) {
            case BreakpointWatcher.Ruptured r -> new StressResult(StressOutcome.BREAKPOINT, r.breakpoint(), null, peakVirtualUsers,
                    peakOfferedRps, r.breakpoint().message());
            case BreakpointWatcher.BaselineUnusable u -> new StressResult(StressOutcome.BASELINE_UNUSABLE, null, null,
                    peakVirtualUsers, peakOfferedRps, u.reason());
            case BreakpointWatcher.Inconclusive i -> new StressResult(StressOutcome.INCONCLUSIVE, null, null, peakVirtualUsers,
                    peakOfferedRps, i.reason());
            case null -> ramp != null && ramp.limitReached() != null && !handle.isCancelled()
                    ? new StressResult(StressOutcome.NO_BREAKPOINT, null, ramp.limitReached(), peakVirtualUsers, peakOfferedRps,
                            noBreakpointMessage(peakVirtualUsers, peakOfferedRps, ramp.limitReached()))
                    : new StressResult(null, null, null, peakVirtualUsers, peakOfferedRps, null);
        };
    }

    /** « Aucune rupture détectée jusqu'à 200 VU / 480 req/s offertes (limite de LoadPilot atteinte : ...). » */
    static String noBreakpointMessage(int peakVirtualUsers, double peakOfferedRps, StressRamp.Limit limit) {
        return "Aucune rupture détectée jusqu'à " + peakVirtualUsers + " VU / " + ExecutionVerdict.format(peakOfferedRps)
                + " req/s offertes (limite de LoadPilot atteinte : " + limitLabel(limit) + ").";
    }

    static String limitLabel(StressRamp.Limit limit) {
        return switch (limit) {
            case TARGET_RPS -> "débit cible maximal";
            case VIRTUAL_USERS -> "utilisateurs virtuels par test";
            case DURATION -> "durée maximale d’un test";
        };
    }
}
