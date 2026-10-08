package com.loadpilot.backend.service.report;

import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.ErrorPoint;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.ExtremeMarker;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.Insight;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.PauseRange;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.ResourceSeries;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.StepEvolution;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.StepSeries;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.StepTrend;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calcul PUR (aucun acces base, entierement testable) de l'analyse dans le temps d'une execution :
 * regroupement des requetes par intervalle de temps, utilisateurs virtuels actifs, debit, temps de
 * reponse moyen et P95, par etape, erreurs par type, et resume « Points clés ».
 *
 * Temps : chaque requete est rangee dans l'intervalle de sa FIN (heure enregistree) ; t0 = debut du
 * premier utilisateur virtuel (ou de la premiere requete). Pas de temps : le plus petit de
 * BUCKET_STEPS qui donne au plus MAX_BUCKETS intervalles (1 s pour un test de 5 min, 2 s pour 10 min,
 * 10 s pour 50 min…) - graphiques lisibles et calcul rapide quelle que soit la duree.
 *
 * Pauses : la duree en pause est retiree du denominateur du debit ; un intervalle entierement en
 * pause n'a ni debit ni temps de reponse (null : la courbe s'interrompt au lieu de tomber a 0).
 */
public final class TimelineAnalyzer {

    static final int MAX_BUCKETS = 300;
    static final int[] BUCKET_STEPS = {1, 2, 5, 10, 15, 30, 60, 120, 300, 600};

    /** Seuils des « Points clés » (voir insights()). */
    static final double PLATEAU_SHARE = 0.9;
    static final double PLATEAU_MORE_USERS = 1.25;
    static final int MIN_ACTIVE_BUCKETS = 8;
    static final int MIN_RAMP_USERS = 3;
    static final double DEGRADATION_RATIO = 1.3;
    static final long DEGRADATION_MIN_MS = 20;

    /** Une requete terminee : fin (epoch ms), temps de reponse, resultat, etape, utilisateur virtuel. */
    public record Sample(long endMs, long responseMs, boolean success, Integer httpStatus, String error, String stepKey,
            String stepName, String method, Integer virtualUser) {
    }

    /** Intervalle de temps absolu [startMs, endMs] (utilisateur virtuel actif, ou pause). */
    public record Interval(long startMs, long endMs) {
    }

    private TimelineAnalyzer() {
    }

    /**
     * @param samples          requetes de l'execution (ordre quelconque)
     * @param recordedVus      intervalles d'activite des VU enregistres pendant l'execution, ou null
     *                         (anciennes executions : deduits des requetes si le VU est connu)
     * @param pauses           intervalles de pause (vides si aucune)
     */
    public static ExecutionTimelineResponse analyze(List<Sample> samples, List<Interval> recordedVus, List<Interval> pauses,
            Resources resources) {
        List<Interval> pauseList = pauses == null ? List.of() : pauses;
        if (samples == null || samples.isEmpty()) {
            return new ExecutionTimelineResponse("NONE", false, 1, 0, List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, 0L, null, null,
                    "Aucune requête enregistrée pour cette exécution : analyse dans le temps impossible.");
        }

        // ---- utilisateurs virtuels actifs : enregistres, sinon deduits (premiere a derniere requete de chaque VU)
        List<Interval> vus = recordedVus != null && !recordedVus.isEmpty() ? recordedVus : deriveVirtualUsers(samples);
        String source = recordedVus != null && !recordedVus.isEmpty() ? "RECORDED" : "DERIVED";
        boolean vusAvailable = !vus.isEmpty();

        long t0 = Long.MAX_VALUE;
        long tEnd = Long.MIN_VALUE;
        for (Sample s : samples) {
            t0 = Math.min(t0, s.endMs() - Math.max(0, s.responseMs()));
            tEnd = Math.max(tEnd, s.endMs());
        }
        for (Interval v : vus) {
            t0 = Math.min(t0, v.startMs());
            tEnd = Math.max(tEnd, v.endMs());
        }
        long span = Math.max(1, tEnd - t0);
        int bucketSeconds = bucketSeconds(span);
        long bucketMs = bucketSeconds * 1000L;
        int n = (int) Math.max(1, (span + bucketMs - 1) / bucketMs);
        if ((long) n * bucketMs <= span) n++;

        // ---- etapes (ordre de premiere apparition)
        Map<String, Integer> stepIndex = new LinkedHashMap<>();
        List<String[]> stepInfo = new ArrayList<>();
        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort((a, b) -> Long.compare(a.endMs(), b.endMs()));
        for (Sample s : sorted) {
            if (!stepIndex.containsKey(s.stepKey())) {
                stepIndex.put(s.stepKey(), stepInfo.size());
                stepInfo.add(new String[] {s.stepKey(), s.stepName(), s.method()});
            }
        }
        int stepCount = stepInfo.size();

        // ---- regroupement
        List<List<Long>> times = newLists(n);
        List<List<List<Long>>> stepTimes = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) stepTimes.add(newLists(n));
        int[] errors = new int[n];
        Map<String, Integer> typeIndex = new LinkedHashMap<>();
        Map<String, Integer> errorCounts = new HashMap<>(); // "bucket|step|type" -> count
        for (Sample s : sorted) {
            int b = (int) Math.min(n - 1, Math.max(0, (s.endMs() - t0) / bucketMs));
            int st = stepIndex.get(s.stepKey());
            times.get(b).add(s.responseMs());
            stepTimes.get(st).get(b).add(s.responseMs());
            if (!s.success()) {
                errors[b]++;
                String type = errorType(s);
                int ti = typeIndex.computeIfAbsent(type, k -> typeIndex.size());
                errorCounts.merge(b + "|" + st + "|" + ti, 1, Integer::sum);
            }
        }

        // ---- series globales
        List<Integer> t = new ArrayList<>();
        List<Integer> active = new ArrayList<>();
        List<Integer> requests = new ArrayList<>();
        List<Double> throughput = new ArrayList<>();
        List<Long> avg = new ArrayList<>();
        List<Long> p95 = new ArrayList<>();
        List<Integer> errorList = new ArrayList<>();
        List<Double> pausedRatio = new ArrayList<>();
        double[] activeSeconds = new double[n];
        for (int b = 0; b < n; b++) {
            long from = t0 + b * bucketMs;
            long to = from + bucketMs;
            double paused = overlapMs(pauseList, from, to) / 1000.0;
            // Dernier intervalle souvent incomplet : debit calcule sur sa duree reelle (jusqu'a la fin du test).
            // (au moins une demi-largeur : un bout d'intervalle de quelques ms ne cree pas un faux pic de debit).
            double length = Math.max(bucketSeconds / 2.0, (Math.min(to, tEnd) - from) / 1000.0);
            activeSeconds[b] = Math.max(0, length - paused);
            boolean fullyPaused = activeSeconds[b] < 0.05 * length;
            t.add(b * bucketSeconds);
            active.add(vusAvailable ? countOverlapping(vus, from, to) : null);
            List<Long> rt = times.get(b);
            requests.add(rt.size());
            throughput.add(fullyPaused ? null : round1(rt.size() / activeSeconds[b]));
            avg.add(fullyPaused || rt.isEmpty() ? null : Math.round(rt.stream().mapToLong(Long::longValue).average().orElse(0)));
            p95.add(fullyPaused ? null : PercentileCalculator.percentile(rt, 95));
            errorList.add(errors[b]);
            pausedRatio.add(round2(Math.min(1, paused / bucketSeconds)));
        }

        // ---- series par etape
        List<StepSeries> steps = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            List<Integer> req = new ArrayList<>();
            List<Double> rps = new ArrayList<>();
            List<Long> sp95 = new ArrayList<>();
            List<Long> all = new ArrayList<>();
            for (int b = 0; b < n; b++) {
                List<Long> rt = stepTimes.get(i).get(b);
                all.addAll(rt);
                boolean fullyPaused = throughput.get(b) == null;
                req.add(rt.size());
                rps.add(fullyPaused ? null : round1(rt.size() / activeSeconds[b]));
                sp95.add(fullyPaused ? null : PercentileCalculator.percentile(rt, 95));
            }
            String[] info = stepInfo.get(i);
            steps.add(new StepSeries(info[0], info[1], info[2], req, rps, sp95, PercentileCalculator.percentile(all, 95)));
        }

        // ---- erreurs (points non nuls uniquement)
        List<ErrorPoint> errorPoints = new ArrayList<>();
        errorCounts.forEach((key, count) -> {
            String[] parts = key.split("\\|");
            errorPoints.add(new ErrorPoint(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), count));
        });
        errorPoints.sort((a, b) -> a.bucket() != b.bucket() ? Integer.compare(a.bucket(), b.bucket())
                : a.step() != b.step() ? Integer.compare(a.step(), b.step()) : Integer.compare(a.type(), b.type()));

        List<PauseRange> pauseRanges = new ArrayList<>();
        for (Interval p : pauseList) {
            pauseRanges.add(new PauseRange(round1((p.startMs() - t0) / 1000.0), round1((p.endMs() - t0) / 1000.0)));
        }

        StepEvolution evolution = StepEvolutionAnalyzer.analyze(sorted, t0, tEnd, vus, pauseList);
        List<Insight> insights = insights(sorted, t0, bucketSeconds, active, throughput, p95, evolution, vusAvailable);
        return new ExecutionTimelineResponse(source, vusAvailable, bucketSeconds, span, t, active, requests, throughput, avg, p95,
                errorList, pausedRatio, steps, new ArrayList<>(typeIndex.keySet()), errorPoints, pauseRanges, insights, evolution,
                resourceSeries(resources, t0, bucketMs, n), t0, extreme(sorted, t0, bucketMs, n, false), extreme(sorted, t0, bucketMs, n, true),
                null);
    }

    /**
     * Requete la plus rapide (ou la plus lente) : meme ordre que les cartes Minimum / Maximum du
     * rapport et que GET /extremes (temps de reponse, puis la plus ancienne d'abord).
     */
    static ExtremeMarker extreme(List<Sample> sortedByEnd, long t0, long bucketMs, int n, boolean slowest) {
        Sample best = null;
        for (Sample s : sortedByEnd) {
            if (best == null || (slowest ? s.responseMs() > best.responseMs() : s.responseMs() < best.responseMs())) best = s;
        }
        if (best == null) return null;
        int b = (int) Math.min(n - 1, Math.max(0, (best.endMs() - t0) / bucketMs));
        return new ExtremeMarker(b, best.responseMs(), round1((best.endMs() - t0) / 1000.0));
    }

    /** Mesures CPU / RAM du processus teste (voir ExecutionTimelineService) ; null = pas de monitoring. */
    public record Resources(String source, int intervalSeconds, String memoryMetric, String message, List<ResourcePoint> samples) {
    }

    public record ResourcePoint(long atMs, Double cpuPercent, Double memoryMb) {
    }

    public static ExecutionTimelineResponse analyze(List<Sample> samples, List<Interval> recordedVus, List<Interval> pauses) {
        return analyze(samples, recordedVus, pauses, null);
    }

    /** Moyenne des mesures de chaque intervalle (mesure rangee a son instant ; hors du test : ignoree). */
    static ResourceSeries resourceSeries(Resources resources, long t0, long bucketMs, int n) {
        if (resources == null) return null;
        double[] cpuSum = new double[n];
        int[] cpuCount = new int[n];
        double[] memSum = new double[n];
        int[] memCount = new int[n];
        int used = 0;
        for (ResourcePoint p : resources.samples()) {
            long offset = p.atMs() - t0;
            if (offset < 0 || offset >= n * bucketMs) continue;
            int b = (int) (offset / bucketMs);
            used++;
            if (p.cpuPercent() != null) {
                cpuSum[b] += p.cpuPercent();
                cpuCount[b]++;
            }
            if (p.memoryMb() != null) {
                memSum[b] += p.memoryMb();
                memCount[b]++;
            }
        }
        List<Double> cpu = new ArrayList<>(n);
        List<Double> mem = new ArrayList<>(n);
        for (int b = 0; b < n; b++) {
            cpu.add(cpuCount[b] == 0 ? null : round1(cpuSum[b] / cpuCount[b]));
            mem.add(memCount[b] == 0 ? null : round1(memSum[b] / memCount[b]));
        }
        return new ResourceSeries(resources.source(), resources.intervalSeconds(), resources.memoryMetric(), resources.message(),
                used, cpu, mem);
    }

    /** Plus petit pas de BUCKET_STEPS donnant au plus MAX_BUCKETS intervalles. */
    static int bucketSeconds(long spanMs) {
        for (int step : BUCKET_STEPS) {
            if (spanMs / (step * 1000.0) <= MAX_BUCKETS) return step;
        }
        return BUCKET_STEPS[BUCKET_STEPS.length - 1];
    }

    /** Type d'erreur : code HTTP, sinon message d'erreur raccourci. */
    static String errorType(Sample s) {
        if (s.httpStatus() != null) return "HTTP " + s.httpStatus();
        String e = s.error() == null ? "Erreur" : s.error();
        return e.length() > 60 ? e.substring(0, 60) + "…" : e;
    }

    // ------------------------------------------------------------------ « Points clés »

    /**
     * Regles simples, volontairement prudentes (indications, jamais des certitudes) :
     *
     * 1. Plafond de debit - analysable seulement avec au moins MIN_ACTIVE_BUCKETS intervalles actifs et
     *    une montee en charge (au moins MIN_RAMP_USERS utilisateurs d'ecart entre le minimum et le
     *    maximum d'utilisateurs actifs). Le debit est lisse (moyenne glissante sur 3 intervalles) ; son
     *    niveau de reference est la moyenne des 10 % de valeurs les plus hautes (jamais un pic isole) ;
     *    tPlateau = premier instant ou il atteint PLATEAU_SHARE (90 %) de ce niveau. Si, APRES cet
     *    instant, le nombre d'utilisateurs actifs augmente encore d'au moins 25 % (PLATEAU_MORE_USERS)
     *    sans que le debit ne depasse ce maximum : « le debit plafonne ». Sinon : « aucun plafond ».
     * 2. Etape la plus degradee - meme calcul que le tableau « Évolution des étapes » (voir
     *    StepEvolutionAnalyzer : P95 des 20 % du debut et de la fin de la phase stable) : premiere etape
     *    du tableau si elle est « Dégradée » ou « À surveiller ».
     * 3. Premieres erreurs - heure de la premiere requete en echec, son type et son etape.
     */
    static List<Insight> insights(List<Sample> sorted, long t0, int bucketSeconds, List<Integer> active, List<Double> throughput,
            List<Long> p95, StepEvolution evolution, boolean vusAvailable) {
        List<Insight> out = new ArrayList<>();
        out.add(plateauInsight(bucketSeconds, active, throughput, p95, vusAvailable));
        out.add(degradationInsight(evolution));
        out.add(errorInsight(sorted, t0));
        return out;
    }

    private static Insight plateauInsight(int bucketSeconds, List<Integer> active, List<Double> throughput, List<Long> p95,
            boolean vusAvailable) {
        List<Integer> idx = new ArrayList<>();
        for (int b = 0; b < throughput.size(); b++) if (throughput.get(b) != null) idx.add(b);
        if (idx.size() < MIN_ACTIVE_BUCKETS) {
            return new Insight("INFO", "Exécution trop courte pour détecter un plafond de débit (moins de "
                    + MIN_ACTIVE_BUCKETS + " intervalles de mesure).");
        }
        if (!vusAvailable) {
            return new Insight("INFO", "Nombre d'utilisateurs virtuels actifs non disponible pour cette exécution : "
                    + "plafond de débit non analysable.");
        }
        int minVu = Integer.MAX_VALUE;
        int maxVu = 0;
        for (int b : idx) {
            minVu = Math.min(minVu, active.get(b));
            maxVu = Math.max(maxVu, active.get(b));
        }
        // Lissage : moyenne glissante sur 3 intervalles actifs (une seconde isolee ne fait pas un plafond).
        double[] smooth = new double[idx.size()];
        for (int i = 0; i < idx.size(); i++) {
            double sum = 0;
            int c = 0;
            for (int j = Math.max(0, i - 1); j <= Math.min(idx.size() - 1, i + 1); j++) {
                sum += throughput.get(idx.get(j));
                c++;
            }
            smooth[i] = sum / c;
        }
        // Niveau de debit de reference : moyenne des 10 % de valeurs lissees les plus hautes - un pic
        // isole (quelques requetes groupees) ne fixe jamais seul le niveau du plafond.
        double[] ordered = smooth.clone();
        java.util.Arrays.sort(ordered);
        int top = Math.max(1, (int) Math.ceil(ordered.length * 0.1));
        double max = 0;
        for (int i = ordered.length - top; i < ordered.length; i++) max += ordered[i];
        max /= top;
        if (maxVu - minVu < MIN_RAMP_USERS) {
            return new Insight("INFO", "Pas de montée en charge (" + maxVu + " utilisateur(s) virtuel(s) presque tout le temps) : "
                    + "plafond non analysable ; débit stable autour de " + fmtRps(max) + " req/s.");
        }
        int plateauPos = 0;
        while (plateauPos < smooth.length - 1 && smooth[plateauPos] < PLATEAU_SHARE * max) plateauPos++;
        int plateauBucket = idx.get(plateauPos);
        int vuAtPlateau = Math.max(1, active.get(plateauBucket));
        int vuAfter = 0;
        for (int i = plateauPos; i < idx.size(); i++) vuAfter = Math.max(vuAfter, active.get(idx.get(i)));
        if (vuAfter >= PLATEAU_MORE_USERS * vuAtPlateau) {
            String text = "Le débit plafonne autour de " + fmtRps(max) + " req/s à partir de " + vuAtPlateau
                    + " utilisateurs virtuels (à " + fmtTime(plateauBucket * (long) bucketSeconds) + ") : jusqu'à " + vuAfter
                    + " utilisateurs ensuite, sans gain de débit";
            Long p95At = firstNonNull(p95, plateauBucket, +1);
            Long p95End = lastNonNull(p95);
            if (p95At != null && p95End != null && p95End >= DEGRADATION_RATIO * p95At && p95End - p95At >= DEGRADATION_MIN_MS) {
                text += ", pendant que le P95 passe de " + p95At + " ms à " + p95End + " ms";
            }
            return new Insight("PLATEAU", text + ".");
        }
        return new Insight("NO_PLATEAU", "Aucun plafond détecté : le débit suit le nombre d'utilisateurs virtuels (jusqu'à "
                + fmtRps(max) + " req/s avec " + maxVu + " utilisateurs).");
    }

    private static Insight degradationInsight(StepEvolution evolution) {
        if (!evolution.comparable()) return new Insight("INFO", evolution.message());
        StepTrend worst = evolution.steps().get(0); // tableau trie : la plus degradee en premier
        if (!StepEvolutionAnalyzer.DEGRADED.equals(worst.trend()) && !StepEvolutionAnalyzer.WATCH.equals(worst.trend())) {
            return new Insight("INFO", "Aucune étape ne se dégrade nettement : P95 du début et de la fin du test proches pour toutes les étapes.");
        }
        return new Insight("DEGRADATION", "L'étape la plus dégradée est " + worst.name() + " : P95 de " + worst.startP95Ms()
                + " ms au début, " + worst.endP95Ms() + " ms à la fin.");
    }

    private static Insight errorInsight(List<Sample> sorted, long t0) {
        Sample firstError = null;
        int total = 0;
        for (Sample s : sorted) {
            if (!s.success()) {
                total++;
                if (firstError == null) firstError = s;
            }
        }
        if (firstError == null) return new Insight("ERRORS", "Aucune erreur pendant l'exécution.");
        return new Insight("ERRORS", "Les premières erreurs apparaissent à " + fmtTime(Math.max(0, firstError.endMs() - t0) / 1000)
                + " (" + errorType(firstError) + " sur " + firstError.stepName() + ") ; " + total + " erreur(s) au total.");
    }

    // ------------------------------------------------------------------ outils

    static List<Interval> deriveVirtualUsers(List<Sample> samples) {
        Map<Integer, long[]> byVu = new HashMap<>();
        for (Sample s : samples) {
            if (s.virtualUser() == null) continue;
            long start = s.endMs() - Math.max(0, s.responseMs());
            byVu.merge(s.virtualUser(), new long[] {start, s.endMs()},
                    (a, b) -> new long[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }
        List<Interval> out = new ArrayList<>();
        byVu.values().forEach(v -> out.add(new Interval(v[0], v[1])));
        return out;
    }

    private static int countOverlapping(List<Interval> intervals, long from, long to) {
        int c = 0;
        for (Interval i : intervals) if (i.startMs() < to && i.endMs() >= from) c++;
        return c;
    }

    private static long overlapMs(List<Interval> intervals, long from, long to) {
        long total = 0;
        for (Interval i : intervals) total += Math.max(0, Math.min(to, i.endMs()) - Math.max(from, i.startMs()));
        return Math.min(total, to - from);
    }

    private static List<List<Long>> newLists(int n) {
        List<List<Long>> lists = new ArrayList<>(n);
        for (int i = 0; i < n; i++) lists.add(new ArrayList<>());
        return lists;
    }

    private static Long firstNonNull(List<Long> values, int from, int dir) {
        for (int i = from; i >= 0 && i < values.size(); i += dir) if (values.get(i) != null) return values.get(i);
        return null;
    }

    private static Long lastNonNull(List<Long> values) {
        List<Long> copy = new ArrayList<>(values);
        Collections.reverse(copy);
        return firstNonNull(copy, 0, +1);
    }

    private static Double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static Double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static String fmtRps(double v) {
        return v >= 10 ? String.valueOf(Math.round(v)) : String.format(Locale.FRANCE, "%.1f", v);
    }

    /** 16 s, 2 min 05 s. */
    static String fmtTime(long seconds) {
        if (seconds < 120) return seconds + " s";
        return (seconds / 60) + " min " + String.format("%02d", seconds % 60) + " s";
    }
}
