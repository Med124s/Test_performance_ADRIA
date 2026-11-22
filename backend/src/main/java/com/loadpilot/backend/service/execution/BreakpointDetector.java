package com.loadpilot.backend.service.execution;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Mode stress : detection du point de rupture par rapport a la baseline, sans seuil saisi. Recoit les fenetres de
 * WINDOW_SECONDS terminees, une par une ; un critere doit etre vrai sur CONFIRMATION_WINDOWS fenetres consecutives.
 * Une fenetre trop maigre pour juger un critere ne compte pas et ne casse pas la serie de ce critere.
 */
public final class BreakpointDetector {

    /** Signe de rupture. */
    public enum Criterion {
        /** P95 tres au-dessus de celui de la baseline. */
        RESPONSE_TIME,
        /** Timeouts, connexions refusees, 429 et 502/503/504 en masse. */
        REFUSALS,
        /** Debit obtenu tres en dessous du debit offert, alors que la montee continue. */
        THROUGHPUT
    }

    /** Reference mesuree pendant la baseline. */
    public record Baseline(Long p95Ms, long responses, double throughputRps) {
    }

    /**
     * Une fenetre terminee : ses compteurs, le debit offert moyen, l'etat de la montee a sa fin et le niveau de charge
     * a son debut (null s'il n'a pas ete note).
     */
    public record WindowView(long startSecond, LiveStats.Window stats, double offeredRps, boolean rising, boolean poolAtLimit,
            LiveStats.LoadLevel loadAtStart) {

        long endSecond() {
            return startSecond + stats.seconds();
        }

        long completed() {
            return stats.requests() + stats.throttled();
        }

        double refusalPercent() {
            long refusals = stats.timeouts() + stats.connectionsRefused() + stats.tooManyRequests() + stats.unavailable();
            return completed() == 0 ? 0.0 : refusals * 100.0 / completed();
        }
    }

    /**
     * Rupture confirmee : criteres confirmes, mesures de la premiere fenetre anormale (debut de l'anomalie, pas
     * moment de la confirmation) et niveau de charge a son debut.
     */
    public record Breakpoint(Set<Criterion> criteria, long onsetSecond, long confirmedSecond, int virtualUsers, double offeredRps,
            double throughputRps, Long p95Ms, Long baselineP95Ms, double refusalPercent, String message) {
    }

    private final Baseline baseline;
    private final Map<Criterion, Integer> streaks = new EnumMap<>(Criterion.class);
    /** Premiere fenetre de la serie en cours, par critere. */
    private final Map<Criterion, WindowView> onsets = new EnumMap<>(Criterion.class);

    public BreakpointDetector(Baseline baseline) {
        this.baseline = baseline;
    }

    /** Baseline mesuree sur [echauffement, echauffement + baseline[ ; null si elle n'a pas assez de reponses. */
    public static Baseline baselineOf(LiveStats.Window window) {
        if (window.responses() < StressSettings.MIN_BASELINE_RESPONSES || window.p95Ms() == null) return null;
        return new Baseline(window.p95Ms(), window.responses(), window.throughputRps());
    }

    /** Motif d'echec quand la baseline est inexploitable. */
    public static String baselineUnusableMessage(LiveStats.Window window) {
        return "Baseline impossible : " + window.responses() + " réponse(s) en " + StressSettings.BASELINE_SECONDS + " s à "
                + StressSettings.BASELINE_RPS + " req/s (au moins " + StressSettings.MIN_BASELINE_RESPONSES
                + " attendues). L'application ne tient pas la charge minimale : aucun point de rupture ne peut être mesuré.";
    }

    public Baseline baseline() {
        return baseline;
    }

    /** Fenetre suivante ; renvoie la rupture si un critere vient d'etre confirme, null sinon. */
    public Breakpoint onWindow(WindowView view) {
        for (Criterion criterion : Criterion.values()) {
            Boolean anomalous = judge(criterion, view);
            if (anomalous == null) continue;
            if (anomalous) {
                if (streaks.getOrDefault(criterion, 0) == 0) onsets.put(criterion, view);
                streaks.merge(criterion, 1, Integer::sum);
            } else {
                streaks.put(criterion, 0);
                onsets.remove(criterion);
            }
        }
        Set<Criterion> confirmed = EnumSet.noneOf(Criterion.class);
        streaks.forEach((criterion, streak) -> {
            if (streak >= StressSettings.CONFIRMATION_WINDOWS) confirmed.add(criterion);
        });
        if (confirmed.isEmpty()) return null;
        // Debut de l'anomalie : la plus ancienne serie confirmee.
        WindowView onset = confirmed.stream().map(onsets::get).min(java.util.Comparator.comparingLong(WindowView::startSecond))
                .orElseThrow();
        return breakpoint(confirmed, onset, view.endSecond());
    }

    /** Fin de la premiere fenetre de la plus ancienne serie d'anomalie en cours (non confirmee), -1 sans anomalie en cours. */
    public long openAnomalyEndSecond() {
        return onsets.values().stream().mapToLong(WindowView::endSecond).min().orElse(-1);
    }

    /**
     * Saturation de LoadPilot confirmee : non concluant, sauf si une anomalie de la cible a commence avant (sa premiere
     * fenetre se termine avant le debut de la saturation) ; on attend alors sa confirmation.
     */
    public static boolean inconclusive(long saturationStartSecond, long openAnomalyEndSecond) {
        return openAnomalyEndSecond < 0 || openAnomalyEndSecond > saturationStartSecond;
    }

    /** true / false : critere vrai ou faux sur cette fenetre ; null : fenetre trop maigre pour juger. */
    private Boolean judge(Criterion criterion, WindowView view) {
        return switch (criterion) {
            case RESPONSE_TIME -> {
                if (view.completed() < StressSettings.MIN_WINDOW_REQUESTS || view.stats().p95Ms() == null || baseline.p95Ms() == null) {
                    yield null;
                }
                long p95 = view.stats().p95Ms();
                yield p95 > StressSettings.RESPONSE_TIME_FACTOR * baseline.p95Ms()
                        && p95 > baseline.p95Ms() + StressSettings.RESPONSE_TIME_MARGIN_MS;
            }
            case REFUSALS -> view.completed() < StressSettings.MIN_WINDOW_REQUESTS ? null
                    : view.refusalPercent() >= StressSettings.REFUSAL_PERCENT;
            case THROUGHPUT -> {
                // Critere suspendu quand la montee ne monte plus ou que LoadPilot ne peut plus ajouter de VU.
                if (!view.rising() || view.poolAtLimit()
                        || view.offeredRps() * view.stats().seconds() < StressSettings.MIN_WINDOW_REQUESTS) {
                    yield null;
                }
                yield view.stats().throughputRps() < StressSettings.THROUGHPUT_RATIO * view.offeredRps();
            }
        };
    }

    private Breakpoint breakpoint(Set<Criterion> criteria, WindowView onset, long confirmedSecond) {
        LiveStats.LoadLevel level = onset.loadAtStart();
        int virtualUsers = level != null ? level.activeVirtualUsers() : 0;
        double offered = level != null ? level.offeredRps() : onset.offeredRps();
        double throughput = onset.stats().throughputRps();
        StringBuilder details = new StringBuilder();
        for (Criterion criterion : criteria) {
            if (!details.isEmpty()) details.append(" ; ");
            details.append(describe(criterion, onset));
        }
        String message = "Point de rupture vers " + ExecutionVerdict.format(offered) + " req/s offertes et " + virtualUsers
                + " utilisateur(s) virtuel(s), à " + elapsed(onset.startSecond()) + " du début : " + details + " (confirmé sur "
                + StressSettings.CONFIRMATION_WINDOWS + " fenêtres de " + StressSettings.WINDOW_SECONDS + " s consécutives).";
        return new Breakpoint(Set.copyOf(criteria), onset.startSecond(), confirmedSecond, virtualUsers, offered, throughput,
                onset.stats().p95Ms(), baseline.p95Ms(), onset.refusalPercent(), message);
    }

    private String describe(Criterion criterion, WindowView onset) {
        return switch (criterion) {
            case RESPONSE_TIME -> "P95 de " + ExecutionVerdict.format(onset.stats().p95Ms()) + " ms, "
                    + ExecutionVerdict.format(onset.stats().p95Ms() / (double) Math.max(1, baseline.p95Ms())) + " × la baseline ("
                    + ExecutionVerdict.format(baseline.p95Ms()) + " ms)";
            case REFUSALS -> ExecutionVerdict.format(onset.refusalPercent())
                    + " % de refus (délais dépassés, connexions refusées, 429, 502/503/504)";
            case THROUGHPUT -> "débit obtenu " + ExecutionVerdict.format(onset.stats().throughputRps()) + " req/s pour "
                    + ExecutionVerdict.format(onset.offeredRps()) + " req/s offertes en moyenne sur la fenêtre";
        };
    }

    /** "3 min 42 s" ou "42 s". */
    static String elapsed(long seconds) {
        return seconds >= 60 ? (seconds / 60) + " min " + (seconds % 60) + " s" : seconds + " s";
    }
}
