package com.loadpilot.backend.service.report;

import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.StepEvolution;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.StepTrend;
import com.loadpilot.backend.service.report.TimelineAnalyzer.Interval;
import com.loadpilot.backend.service.report.TimelineAnalyzer.Sample;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcul PUR du tableau « Évolution des étapes pendant le test » : P95 de chaque etape au debut et a
 * la fin du test, evolution et tendance.
 *
 * Temps « actif » : temps depuis le debut du test, pauses retirees (une pause ne compte pas dans les
 * periodes comparees).
 *
 * Phase stable : du debut du test jusqu'au moment ou les utilisateurs virtuels commencent a partir
 * (dernier instant ou le nombre maximal de VU est actif) - la fin, ou il ne reste que quelques VU,
 * fausserait la comparaison. Sans VU connus : jusqu'a la derniere requete.
 *
 * Debut / fin : les premiers et les derniers START_END_SHARE (20 %) de cette phase stable. Comparaison
 * possible seulement si la phase stable dure au moins MIN_STABLE_SECONDS et, pour une etape, avec au
 * moins MIN_WINDOW_REQUESTS requetes dans chacune des deux periodes.
 *
 * Tendance (surtout la hausse en millisecondes ; le pourcentage evite d'alerter sur une etape lente
 * dont le P95 varie un peu) :
 *   - Degradee    : hausse >= DEGRADED_MIN_MS (100 ms) ET >= DEGRADED_MIN_RATIO (30 %) ;
 *   - A surveiller : hausse >= WATCH_MIN_MS (30 ms) ET >= WATCH_MIN_RATIO (15 %) ;
 *   - Stable      : sinon (hausse faible, ou baisse).
 * Une etape rapide qui passe de 20 a 40 ms (+20 ms) reste donc « Stable ».
 *
 * Tri : Degradee, puis A surveiller, puis Stable (chaque groupe par hausse en ms decroissante), puis
 * les etapes sans comparaison possible.
 */
public final class StepEvolutionAnalyzer {

    static final double START_END_SHARE = 0.2;
    static final long MIN_STABLE_SECONDS = 20;
    static final int MIN_WINDOW_REQUESTS = 5;
    static final long WATCH_MIN_MS = 30;
    static final double WATCH_MIN_RATIO = 0.15;
    static final long DEGRADED_MIN_MS = 100;
    static final double DEGRADED_MIN_RATIO = 0.30;

    public static final String DEGRADED = "DEGRADED";
    public static final String WATCH = "WATCH";
    public static final String STABLE = "STABLE";
    public static final String INSUFFICIENT = "INSUFFICIENT";

    private StepEvolutionAnalyzer() {
    }

    /**
     * @param sorted requetes triees par heure de fin
     * @param t0     debut du test (epoch ms)
     * @param tEnd   fin du test (epoch ms)
     * @param vus    intervalles d'activite des utilisateurs virtuels (vide si inconnus)
     * @param pauses intervalles de pause
     */
    public static StepEvolution analyze(List<Sample> sorted, long t0, long tEnd, List<Interval> vus, List<Interval> pauses) {
        long stableEnd = Math.min(tEnd, vus.isEmpty() ? tEnd : lastMomentAtMaxUsers(vus));
        if (stableEnd <= t0) stableEnd = tEnd;
        double stableSeconds = activeMs(t0, stableEnd, pauses) / 1000.0;
        double startTo = stableSeconds * START_END_SHARE;
        double endFrom = stableSeconds * (1 - START_END_SHARE);

        // ---- regroupement par etape (ordre de premiere apparition)
        Map<String, Acc> steps = new LinkedHashMap<>();
        for (Sample s : sorted) {
            Acc acc = steps.computeIfAbsent(s.stepKey(), k -> new Acc(s));
            double at = activeMs(t0, s.endMs(), pauses) / 1000.0;
            acc.total++;
            if (s.endMs() > stableEnd) continue; // depart des utilisateurs : hors comparaison
            if (at <= startTo) acc.start.add(s.responseMs());
            else if (at >= endFrom) acc.end.add(s.responseMs());
        }

        List<StepTrend> rows = new ArrayList<>();
        for (Map.Entry<String, Acc> e : steps.entrySet()) {
            Acc a = e.getValue();
            boolean enough = a.start.size() >= MIN_WINDOW_REQUESTS && a.end.size() >= MIN_WINDOW_REQUESTS;
            Long startP95 = a.start.isEmpty() ? null : PercentileCalculator.percentile(a.start, 95);
            Long endP95 = a.end.isEmpty() ? null : PercentileCalculator.percentile(a.end, 95);
            Long delta = enough ? endP95 - startP95 : null;
            Double percent = enough && startP95 > 0 ? Math.round(1000.0 * delta / startP95) / 10.0 : null;
            String trend = enough ? trend(startP95, endP95) : INSUFFICIENT;
            rows.add(new StepTrend(e.getKey(), a.name, a.method, trend, startP95, endP95, a.start.size(), a.end.size(), a.total,
                    delta, percent));
        }
        rows.sort(Comparator.comparingInt((StepTrend r) -> rank(r.trend()))
                .thenComparing(r -> r.deltaMs() == null ? Long.MIN_VALUE : r.deltaMs(), Comparator.reverseOrder()));

        String message = null;
        if (stableSeconds < MIN_STABLE_SECONDS) {
            message = "Exécution trop courte pour comparer le début et la fin du test : " + Math.round(stableSeconds)
                    + " s de charge stable (au moins " + MIN_STABLE_SECONDS + " s nécessaires).";
        } else if (rows.stream().allMatch(r -> INSUFFICIENT.equals(r.trend()))) {
            message = "Pas assez de requêtes au début et à la fin du test pour comparer les étapes (au moins "
                    + MIN_WINDOW_REQUESTS + " par étape dans chaque période).";
        }
        return new StepEvolution(message == null, message, round1(stableSeconds), 0, round1(startTo), round1(endFrom),
                round1(stableSeconds), rows);
    }

    /** Regle des badges (voir Javadoc de la classe). */
    public static String trend(long startP95, long endP95) {
        long delta = endP95 - startP95;
        double ratio = startP95 <= 0 ? (delta > 0 ? Double.MAX_VALUE : 0) : (double) delta / startP95;
        if (delta >= DEGRADED_MIN_MS && ratio >= DEGRADED_MIN_RATIO) return DEGRADED;
        if (delta >= WATCH_MIN_MS && ratio >= WATCH_MIN_RATIO) return WATCH;
        return STABLE;
    }

    private static int rank(String trend) {
        return switch (trend) {
            case DEGRADED -> 0;
            case WATCH -> 1;
            case STABLE -> 2;
            default -> 3;
        };
    }

    /** Dernier instant ou le nombre maximal d'utilisateurs virtuels est actif (debut de leur depart). */
    static long lastMomentAtMaxUsers(List<Interval> vus) {
        List<long[]> events = new ArrayList<>(); // {instant, +1 | -1}
        for (Interval v : vus) {
            events.add(new long[] {v.startMs(), 1});
            events.add(new long[] {v.endMs(), -1});
        }
        // A instant egal, les arrivees avant les departs (un relais n'est pas un depart).
        events.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(b[1], a[1]));
        int count = 0;
        int max = 0;
        for (long[] e : events) {
            count += (int) e[1];
            max = Math.max(max, count);
        }
        count = 0;
        long last = events.get(events.size() - 1)[0];
        for (long[] e : events) {
            boolean wasMax = count == max;
            count += (int) e[1];
            if (wasMax && count < max) last = e[0];
        }
        return last;
    }

    /** Duree entre from et to, pauses retirees. */
    static long activeMs(long from, long to, List<Interval> pauses) {
        if (to <= from) return 0;
        long paused = 0;
        for (Interval p : pauses) paused += Math.max(0, Math.min(to, p.endMs()) - Math.max(from, p.startMs()));
        return Math.max(0, to - from - paused);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static final class Acc {
        final String name;
        final String method;
        final List<Long> start = new ArrayList<>();
        final List<Long> end = new ArrayList<>();
        int total;

        Acc(Sample first) {
            name = first.stepName();
            method = first.method();
        }
    }
}
