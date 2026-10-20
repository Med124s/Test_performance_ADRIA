package com.loadpilot.backend.service.execution;

import java.util.Arrays;
import java.util.List;

/**
 * Totaux d'une execution tenus au fil de l'eau, sans garder les resultats eux-memes : ceux-ci partent
 * en base pendant le test (voir ResultStream). Sert au statut final (ExecutionVerdict) : nombre de
 * requetes, echecs, au moins une reponse recue, premiere erreur, et P95 EXACT (meme methode
 * « nearest rank » que PercentileCalculator) a partir des seuls temps de reponse, 4 octets par requete
 * au lieu d'un StepOutcome complet.
 *
 * Thread-safe : alimente par tous les utilisateurs virtuels d'une execution (verrou court, jamais
 * dispute longtemps : un ajout dans un tableau).
 */
public final class ResultSummary {

    private long total;
    private long failures;
    private boolean anyResponse;
    /** Erreur de la premiere requete recue (message « Application injoignable »). */
    private String firstOutcomeError;
    /** Erreur du premier echec. */
    private String firstFailureError;
    private boolean anyFailure;
    private int[] times = new int[256];
    private int size;

    public static ResultSummary of(List<StepOutcome> outcomes) {
        ResultSummary summary = new ResultSummary();
        for (StepOutcome o : outcomes) summary.add(o);
        return summary;
    }

    public synchronized void add(StepOutcome o) {
        if (total == 0) firstOutcomeError = o.error();
        total++;
        if (o.httpStatus() != null) anyResponse = true;
        if (!o.success()) {
            if (!anyFailure) firstFailureError = o.error();
            anyFailure = true;
            failures++;
        }
        if (size == times.length) times = Arrays.copyOf(times, times.length * 2);
        times[size++] = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, o.responseTimeMs()));
    }

    public synchronized long total() {
        return total;
    }

    public synchronized long failures() {
        return failures;
    }

    public synchronized boolean anyResponse() {
        return anyResponse;
    }

    public synchronized String firstOutcomeError() {
        return firstOutcomeError;
    }

    /** Erreur du premier echec, null s'il n'y en a aucun. */
    public synchronized String firstFailureError() {
        return firstFailureError;
    }

    /** Percentile p (0 &lt; p &le; 100) des temps de reponse, null sans requete - meme rang que PercentileCalculator. */
    public Long percentile(double p) {
        int[] sorted;
        synchronized (this) {
            if (size == 0) return null;
            sorted = Arrays.copyOf(times, size);
        }
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        rank = Math.max(1, Math.min(sorted.length, rank));
        return (long) sorted[rank - 1];
    }

    /** Memoire tenue, en octets (garde-fou memoire). */
    public synchronized long approxBytes() {
        return 4L * times.length;
    }
}
