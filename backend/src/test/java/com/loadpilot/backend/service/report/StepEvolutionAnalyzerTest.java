package com.loadpilot.backend.service.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.StepEvolution;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse.StepTrend;
import com.loadpilot.backend.service.report.TimelineAnalyzer.Interval;
import com.loadpilot.backend.service.report.TimelineAnalyzer.Sample;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;

/** Tableau « Évolution des étapes » : periodes debut / fin, pauses, tri, badges, test trop court. */
class StepEvolutionAnalyzerTest {

    private static final long T0 = 1_700_000_000_000L;

    /** 5 requetes par seconde pendant `seconds` s ; temps de reponse selon la seconde. */
    private static void step(List<Sample> out, String name, int seconds, LongUnaryOperator rtAtSecond) {
        for (int s = 0; s < seconds; s++) {
            for (int i = 0; i < 5; i++) {
                long rt = rtAtSecond.applyAsLong(s);
                out.add(new Sample(T0 + s * 1000L + 100 + i * 150, rt, true, 200, null, name, name, "GET", 0));
            }
        }
    }

    private static StepEvolution analyze(List<Sample> samples, List<Interval> vus, List<Interval> pauses) {
        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort((a, b) -> Long.compare(a.endMs(), b.endMs()));
        long end = sorted.get(sorted.size() - 1).endMs();
        for (Interval v : vus) end = Math.max(end, v.endMs());
        return StepEvolutionAnalyzer.analyze(sorted, T0, end, vus, pauses);
    }

    private static StepTrend row(StepEvolution e, String name) {
        return e.steps().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void startAndEndP95_onTheFirstAndLast20PercentOfTheStablePhase() {
        List<Sample> samples = new ArrayList<>();
        // 0-9 s : 100 ms ; 10-39 s : 150 ms ; 40-49 s : 300 ms (debut = 0-10 s, fin = 40-50 s)
        step(samples, "Solde", 50, s -> s < 10 ? 100 : s < 40 ? 150 : 300);
        StepEvolution e = analyze(samples, List.of(new Interval(T0, T0 + 50_000)), List.of());

        assertThat(e.comparable()).isTrue();
        assertThat(e.startToSeconds()).isEqualTo(10.0);
        assertThat(e.endFromSeconds()).isEqualTo(40.0);
        StepTrend r = row(e, "Solde");
        assertThat(r.startP95Ms()).isEqualTo(100L);
        assertThat(r.endP95Ms()).isEqualTo(300L);
        assertThat(r.deltaMs()).isEqualTo(200L);
        assertThat(r.deltaPercent()).isEqualTo(200.0);
        assertThat(r.totalRequests()).isEqualTo(250);
        assertThat(r.startRequests()).isEqualTo(50);
        assertThat(r.trend()).isEqualTo(StepEvolutionAnalyzer.DEGRADED);
    }

    @Test
    void pausesAreExcluded_andTheDepartureOfUsersIsNotCompared() {
        List<Sample> samples = new ArrayList<>();
        // 40 s de charge a 100 ms avec une pause de 20 s au milieu (aucune requete), puis le depart
        // des utilisateurs (60-70 s : un seul VU restant, tres lent) qui ne doit pas compter.
        step(samples, "Solde", 70, s -> s >= 60 ? 900 : 100);
        samples.removeIf(x -> x.endMs() >= T0 + 20_000 && x.endMs() < T0 + 40_000);
        List<Interval> vus = List.of(new Interval(T0, T0 + 60_000), new Interval(T0, T0 + 70_000));
        StepEvolution e = analyze(samples, vus, List.of(new Interval(T0 + 20_000, T0 + 40_000)));

        assertThat(e.stableSeconds()).as("60 s jusqu'au depart d'un VU, moins 20 s de pause").isEqualTo(40.0);
        assertThat(e.endFromSeconds()).isEqualTo(32.0);
        StepTrend r = row(e, "Solde");
        assertThat(r.endP95Ms()).as("le depart des utilisateurs (900 ms) n'est pas compte").isEqualTo(100L);
        assertThat(r.trend()).isEqualTo(StepEvolutionAnalyzer.STABLE);
    }

    @Test
    void badgeRule_mainlyOnMilliseconds_aFastStepGoingFrom20To40msIsNotDegraded() {
        assertThat(StepEvolutionAnalyzer.trend(20, 40)).as("+20 ms : hausse faible").isEqualTo(StepEvolutionAnalyzer.STABLE);
        assertThat(StepEvolutionAnalyzer.trend(20, 60)).as("+40 ms et +200 %").isEqualTo(StepEvolutionAnalyzer.WATCH);
        assertThat(StepEvolutionAnalyzer.trend(280, 420)).as("+140 ms et +50 %").isEqualTo(StepEvolutionAnalyzer.DEGRADED);
        assertThat(StepEvolutionAnalyzer.trend(2000, 2150)).as("+150 ms mais +7,5 % : etape lente").isEqualTo(StepEvolutionAnalyzer.STABLE);
        assertThat(StepEvolutionAnalyzer.trend(500, 650)).as("+150 ms et +30 %").isEqualTo(StepEvolutionAnalyzer.DEGRADED);
        assertThat(StepEvolutionAnalyzer.trend(500, 600)).as("+100 ms mais +20 %").isEqualTo(StepEvolutionAnalyzer.WATCH);
        assertThat(StepEvolutionAnalyzer.trend(300, 250)).as("amelioration").isEqualTo(StepEvolutionAnalyzer.STABLE);

        List<Sample> samples = new ArrayList<>();
        step(samples, "Santé", 50, s -> s < 25 ? 20 : 40);
        StepEvolution e = analyze(samples, List.of(), List.of());
        assertThat(row(e, "Santé").trend()).isEqualTo(StepEvolutionAnalyzer.STABLE);
    }

    @Test
    void sortedFromMostDegradedToMostStable() {
        List<Sample> samples = new ArrayList<>();
        step(samples, "Santé", 50, s -> s < 25 ? 20 : 40);          // +20 ms : stable
        step(samples, "Virement", 50, s -> s < 25 ? 300 : 450);     // +150 ms, 50 % : degradee
        step(samples, "Historique", 50, s -> s < 25 ? 200 : 250);   // +50 ms, 25 % : a surveiller
        step(samples, "Solde", 50, s -> s < 25 ? 280 : 520);        // +240 ms : la plus degradee
        step(samples, "Facture", 50, s -> s < 25 ? 300 : 280);      // -20 ms : stable
        StepEvolution e = analyze(samples, List.of(), List.of());

        assertThat(e.steps()).extracting(StepTrend::name).containsExactly("Solde", "Virement", "Historique", "Santé", "Facture");
        assertThat(e.steps()).extracting(StepTrend::trend).containsExactly("DEGRADED", "DEGRADED", "WATCH", "STABLE", "STABLE");
    }

    @Test
    void tooShortRun_orTooFewRequests_isExplainedInsteadOfCompared() {
        List<Sample> samples = new ArrayList<>();
        step(samples, "Solde", 12, s -> 100);
        StepEvolution e = analyze(samples, List.of(), List.of());
        assertThat(e.comparable()).isFalse();
        assertThat(e.message()).startsWith("Exécution trop courte pour comparer le début et la fin du test : 12 s de charge stable");

        List<Sample> rare = new ArrayList<>();
        for (int s = 0; s < 60; s += 6) rare.add(new Sample(T0 + s * 1000L, 100, true, 200, null, "Rare", "Rare", "GET", 0));
        StepEvolution few = analyze(rare, List.of(), List.of());
        assertThat(few.comparable()).isFalse();
        assertThat(few.message()).startsWith("Pas assez de requêtes au début et à la fin du test");
        assertThat(few.steps().get(0).trend()).isEqualTo(StepEvolutionAnalyzer.INSUFFICIENT);
    }

    @Test
    void lastMomentAtMaxUsers_isWhenTheFirstUserLeavesAfterThePeak() {
        List<Interval> vus = List.of(new Interval(0, 100), new Interval(10, 80), new Interval(20, 90));
        assertThat(StepEvolutionAnalyzer.lastMomentAtMaxUsers(vus)).isEqualTo(80);
        assertThat(StepEvolutionAnalyzer.lastMomentAtMaxUsers(List.of(new Interval(0, 50), new Interval(0, 50)))).isEqualTo(50);
    }
}
