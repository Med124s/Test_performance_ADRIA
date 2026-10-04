package com.loadpilot.backend.service.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.service.report.TimelineAnalyzer.Interval;
import com.loadpilot.backend.service.report.TimelineAnalyzer.Sample;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Analyse dans le temps : regroupement, pauses, plafond, degradation, premieres erreurs, cas limites. */
class TimelineAnalyzerTest {

    private static final long T0 = 1_700_000_000_000L;

    private static Sample ok(long atMs, long rt, String step, int vu) {
        return new Sample(T0 + atMs, rt, true, 200, null, step, step, "GET", vu);
    }

    private static Sample ko(long atMs, int status, String step, int vu) {
        return new Sample(T0 + atMs, 50, false, status, "Reponse HTTP " + status, step, step, "POST", vu);
    }

    @Test
    void perSecondBuckets_throughputAndP95_andBucketSizeGrowsWithDuration() {
        List<Sample> samples = new ArrayList<>();
        for (int s = 0; s < 10; s++) {
            for (int i = 0; i < 4; i++) samples.add(ok(s * 1000L + 100 + i * 200, 100 + i * 10, "A", 0));
        }
        ExecutionTimelineResponse r = TimelineAnalyzer.analyze(samples, List.of(new Interval(T0, T0 + 10_000)), List.of());
        assertThat(r.bucketSeconds()).isEqualTo(1);
        assertThat(r.source()).isEqualTo("RECORDED");
        assertThat(r.requests().get(3)).isEqualTo(4);
        assertThat(r.throughput().get(3)).isEqualTo(4.0);
        assertThat(r.p95Ms().get(3)).isEqualTo(130L);
        assertThat(r.activeVirtualUsers().get(5)).isEqualTo(1);

        assertThat(TimelineAnalyzer.bucketSeconds(180_000)).isEqualTo(1);
        assertThat(TimelineAnalyzer.bucketSeconds(600_000)).isEqualTo(2);
        assertThat(TimelineAnalyzer.bucketSeconds(1_200_000)).isEqualTo(5);
        assertThat(TimelineAnalyzer.bucketSeconds(3 * 3_600_000L)).isEqualTo(60);
    }

    @Test
    void pausedSecondsAreExcluded_noFalseZero() {
        List<Sample> samples = new ArrayList<>();
        // Requetes a 0-3 s et 8-10 s ; pause de 3 s a 8 s.
        for (int s : new int[] {0, 1, 2, 8, 9}) for (int i = 0; i < 5; i++) samples.add(ok(s * 1000L + 100 + i * 150, 100, "A", 0));
        ExecutionTimelineResponse r = TimelineAnalyzer.analyze(samples, List.of(new Interval(T0, T0 + 10_000)),
                List.of(new Interval(T0 + 3_000, T0 + 8_000)));
        assertThat(r.throughput().get(4)).as("seconde en pause : pas de debit (courbe interrompue)").isNull();
        assertThat(r.p95Ms().get(5)).isNull();
        assertThat(r.pausedRatio().get(5)).isEqualTo(1.0);
        assertThat(r.throughput().get(1)).isEqualTo(5.0);
        assertThat(r.pauses()).hasSize(1);
        assertThat(r.pauses().get(0).fromSeconds()).isEqualTo(3.0);
        assertThat(r.pauses().get(0).toSeconds()).isEqualTo(8.0);
    }

    /** Montee en charge : 1 VU de plus par seconde jusqu'a 20 ; le serveur sature a 40 req/s des 8 VU. */
    private static List<Sample> rampWithCeiling(boolean ceiling) {
        List<Sample> samples = new ArrayList<>();
        for (int s = 0; s < 30; s++) {
            int vus = Math.min(20, s + 1);
            int perVu = 5;
            int total = ceiling ? Math.min(40, vus * perVu) : vus * perVu;
            long rt = ceiling && vus * perVu > 40 ? 100 + (vus * perVu - 40) * 10L : 100;
            for (int i = 0; i < total; i++) samples.add(ok(s * 1000L + (i * 1000L / total), rt, "Virement", i % vus));
        }
        return samples;
    }

    private static List<Interval> rampVus() {
        List<Interval> vus = new ArrayList<>();
        for (int v = 0; v < 20; v++) vus.add(new Interval(T0 + v * 1000L, T0 + 30_000));
        return vus;
    }

    @Test
    void plateauDetected_withUsersAndTime_andP95Rise() {
        ExecutionTimelineResponse r = TimelineAnalyzer.analyze(rampWithCeiling(true), rampVus(), List.of());
        String plateau = r.insights().get(0).text();
        assertThat(r.insights().get(0).kind()).isEqualTo("PLATEAU");
        assertThat(plateau).contains("Le débit plafonne autour de 40 req/s à partir de 8 utilisateurs virtuels");
        assertThat(plateau).contains("P95 passe de");
        assertThat(r.insights().get(1).kind()).isEqualTo("DEGRADATION");
        assertThat(r.insights().get(1).text()).startsWith("L'étape la plus dégradée est Virement : P95 de ")
                .endsWith(" ms à la fin.");
    }

    /** Cas de l'essai reel : le debit cesse de monter vers 30 VU (~108 req/s) ; un pic isole a 128 req/s ne doit pas le masquer. */
    @Test
    void softPlateau_isDetected_despiteAnIsolatedPeak() {
        List<Sample> samples = new ArrayList<>();
        List<Interval> vus = new ArrayList<>();
        for (int v = 0; v < 40; v++) vus.add(new Interval(T0 + v * 3000L, T0 + 180_000));
        for (int s = 0; s < 180; s++) {
            int active = Math.min(40, s / 3 + 1);
            int rps = active <= 30 ? Math.round(active * 3.6f) : 108;
            if (s == 170) rps = 128; // pic isole
            for (int i = 0; i < rps; i++) samples.add(ok(s * 1000L + 100 + (i * 900L / rps), 100, "Solde", i % active));
        }
        ExecutionTimelineResponse r = TimelineAnalyzer.analyze(samples, vus, List.of());
        assertThat(r.insights().get(0).kind()).isEqualTo("PLATEAU");
        assertThat(r.insights().get(0).text()).containsPattern("Le débit plafonne autour de 1(0[5-9]|1\\d) req/s à partir de (2[6-9]|3[0-1]) utilisateurs");
    }

    @Test
    void mostDegradedStep_isTheLargestIncreaseInMilliseconds_notTheLargestRatio() {
        List<Sample> samples = new ArrayList<>();
        for (int s = 0; s < 30; s++) {
            boolean late = s >= 20;
            for (int i = 0; i < 5; i++) {
                samples.add(ok(s * 1000L + 300 + i * 100, late ? 120 : 20, "Santé", 0));   // x6 mais +100 ms
                samples.add(ok(s * 1000L + 300 + i * 100, late ? 490 : 280, "Solde", 1));  // +210 ms
            }
        }
        ExecutionTimelineResponse r = TimelineAnalyzer.analyze(samples, null, List.of());
        assertThat(r.insights().get(1).text()).isEqualTo("L'étape la plus dégradée est Solde : P95 de 280 ms au début, 490 ms à la fin.");
    }

    @Test
    void noPlateau_whenThroughputFollowsUsers() {
        ExecutionTimelineResponse r = TimelineAnalyzer.analyze(rampWithCeiling(false), rampVus(), List.of());
        assertThat(r.insights().get(0).kind()).isEqualTo("NO_PLATEAU");
        assertThat(r.insights().get(0).text()).startsWith("Aucun plafond détecté : le débit suit le nombre d'utilisateurs virtuels");
        assertThat(r.insights().get(1).text()).startsWith("Aucune étape ne se dégrade nettement");
    }

    @Test
    void firstErrors_typeAndStep_andErrorPointsByType() {
        List<Sample> samples = new ArrayList<>();
        for (int s = 0; s < 20; s++) samples.add(ok(s * 1000L, 100, "Solde", 0));
        samples.add(ko(18_200, 409, "Virement national", 0));
        samples.add(ko(19_100, 500, "Virement national", 0));
        samples.add(new Sample(T0 + 19_500, 10_000, false, null, "Delai d'attente depasse (10s)", "Solde", "Solde", "GET", 0));
        ExecutionTimelineResponse r = TimelineAnalyzer.analyze(samples, null, List.of());
        assertThat(r.source()).isEqualTo("DERIVED");
        assertThat(r.insights().get(2).text())
                .isEqualTo("Les premières erreurs apparaissent à 18 s (HTTP 409 sur Virement national) ; 3 erreur(s) au total.");
        assertThat(r.errorTypes()).containsExactly("HTTP 409", "HTTP 500", "Delai d'attente depasse (10s)");
        assertThat(r.errors().get(18)).isEqualTo(1);
        assertThat(r.errorPoints()).hasSize(3);
    }

    @Test
    void shortRun_noRamp_noData_areStatedNotInvented() {
        List<Sample> few = List.of(ok(0, 100, "A", 0), ok(500, 100, "A", 0), ok(1500, 100, "A", 0));
        ExecutionTimelineResponse shortRun = TimelineAnalyzer.analyze(few, null, List.of());
        assertThat(shortRun.insights().get(0).text()).startsWith("Exécution trop courte pour détecter un plafond");
        assertThat(shortRun.insights().get(2).text()).isEqualTo("Aucune erreur pendant l'exécution.");

        List<Sample> flat = new ArrayList<>();
        for (int s = 0; s < 20; s++) for (int v = 0; v < 5; v++) flat.add(ok(s * 1000L + v * 100, 100, "A", v));
        List<Interval> together = new ArrayList<>();
        for (int v = 0; v < 5; v++) together.add(new Interval(T0, T0 + 20_000));
        assertThat(TimelineAnalyzer.analyze(flat, together, List.of()).insights().get(0).text()).startsWith("Pas de montée en charge");

        List<Sample> noVu = List.of(new Sample(T0 + 1000, 100, true, 200, null, "A", "A", "GET", null));
        assertThat(TimelineAnalyzer.analyze(noVu, null, List.of()).virtualUsersAvailable()).isFalse();

        ExecutionTimelineResponse empty = TimelineAnalyzer.analyze(List.of(), null, List.of());
        assertThat(empty.source()).isEqualTo("NONE");
        assertThat(empty.message()).startsWith("Aucune requête enregistrée");
    }
}
