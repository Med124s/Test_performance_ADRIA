package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.service.execution.BreakpointDetector.Criterion;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BreakpointDetectorTest {

    private static final int W = StressSettings.WINDOW_SECONDS;
    private static final BreakpointDetector.Baseline BASELINE = new BreakpointDetector.Baseline(100L, 60, 2.0);

    /** Fenetre synthetique : "achievedRps" req/s terminees, dont "refusals" timeouts ; niveau de charge note a son debut. */
    private static BreakpointDetector.WindowView window(long start, double achievedRps, Long p95, long refusals, double offeredRps,
            boolean rising, boolean poolAtLimit) {
        return window(start, achievedRps, p95, refusals, offeredRps, rising, poolAtLimit, false);
    }

    private static BreakpointDetector.WindowView window(long start, double achievedRps, Long p95, long refusals, double offeredRps,
            boolean rising, boolean poolAtLimit, boolean poolBehind) {
        long requests = Math.round(achievedRps * W);
        LiveStats.Window stats = new LiveStats.Window(requests, refusals, 0, requests - refusals, p95 == null ? 0 : p95 * requests,
                p95, refusals, 0, 0, 0, W);
        return new BreakpointDetector.WindowView(start, stats, offeredRps, rising, poolAtLimit,
                new LiveStats.LoadLevel(start, (int) start, offeredRps), poolBehind);
    }

    private static BreakpointDetector.WindowView normal(long start) {
        return window(start, 20, 120L, 0, 20, true, false);
    }

    private static BreakpointDetector.WindowView slow(long start) {
        return window(start, 20, 400L, 0, 20, true, false);
    }

    @Test
    void baseline_needsEnoughResponses() {
        LiveStats.Window thin = new LiveStats.Window(29, 0, 0, 29, 2900, 100L, 0, 0, 0, 0, 30);
        assertThat(BreakpointDetector.baselineOf(thin)).isNull();
        assertThat(BreakpointDetector.baselineUnusableMessage(thin)).startsWith("Baseline impossible : 29 réponse(s) en 30 s à 2 req/s");

        LiveStats.Window enough = new LiveStats.Window(30, 0, 0, 30, 3000, 100L, 0, 0, 0, 0, 30);
        assertThat(BreakpointDetector.baselineOf(enough)).isEqualTo(new BreakpointDetector.Baseline(100L, 30, 1.0));
    }

    @Test
    void noisyButHealthyTarget_neverBreaks() {
        BreakpointDetector detector = new BreakpointDetector(BASELINE);
        for (int i = 0; i < 40; i++) {
            // P95 x 2,5, 4 % de refus, debit a 85 % de l'offre : sous chaque seuil.
            assertThat(detector.onWindow(window(40 + i * W, 17, 250L, 4, 20, true, false))).as("fenetre %d", i).isNull();
        }
    }

    @Test
    void responseTimeExplosion_onThreeConsecutiveWindows_isABreakpoint_reportedAtItsOnset() {
        BreakpointDetector detector = new BreakpointDetector(BASELINE);
        assertThat(detector.onWindow(normal(40))).isNull();
        assertThat(detector.onWindow(slow(45))).isNull();
        assertThat(detector.openAnomalyEndSecond()).isEqualTo(50);
        assertThat(detector.onWindow(slow(50))).isNull();
        BreakpointDetector.Breakpoint breakpoint = detector.onWindow(slow(55));

        assertThat(breakpoint).isNotNull();
        assertThat(breakpoint.criteria()).containsExactly(Criterion.RESPONSE_TIME);
        assertThat(breakpoint.onsetSecond()).isEqualTo(45);
        assertThat(breakpoint.confirmedSecond()).isEqualTo(60);
        assertThat(breakpoint.virtualUsers()).isEqualTo(45);
        assertThat(breakpoint.p95Ms()).isEqualTo(400);
        assertThat(breakpoint.baselineP95Ms()).isEqualTo(100);
        assertThat(breakpoint.message()).isEqualTo("Point de rupture vers 20 req/s offertes et 45 utilisateur(s) virtuel(s), à 45 s du début : "
                + "P95 de 400 ms, 4 × la baseline (100 ms) (confirmé sur 3 fenêtres de 5 s consécutives).");
    }

    @Test
    void responseTime_aboveTheFactorButWithinTheMargin_isNotABreakpoint() {
        BreakpointDetector detector = new BreakpointDetector(new BreakpointDetector.Baseline(10L, 60, 2.0));
        for (int i = 0; i < 5; i++) assertThat(detector.onWindow(window(40 + i * W, 20, 150L, 0, 20, true, false))).isNull();
    }

    @Test
    void aNormalWindow_breaksTheStreak() {
        BreakpointDetector detector = new BreakpointDetector(BASELINE);
        detector.onWindow(slow(40));
        detector.onWindow(slow(45));
        assertThat(detector.onWindow(normal(50))).isNull();
        assertThat(detector.openAnomalyEndSecond()).isEqualTo(-1);
        detector.onWindow(slow(55));
        assertThat(detector.onWindow(slow(60))).isNull();
        assertThat(detector.onWindow(slow(65)).onsetSecond()).isEqualTo(55);
    }

    @Test
    void aThinWindow_neitherCountsNorBreaksTheStreak() {
        BreakpointDetector detector = new BreakpointDetector(BASELINE);
        detector.onWindow(slow(40));
        // 3 requetes en 5 s : trop peu pour juger le temps de reponse ou les refus (debit offert faible aussi).
        assertThat(detector.onWindow(window(45, 0.6, 900L, 0, 3, true, false))).isNull();
        assertThat(detector.onWindow(slow(50))).isNull();
        assertThat(detector.onWindow(slow(55)).onsetSecond()).isEqualTo(40);
    }

    @Test
    void massiveRefusals_areABreakpoint() {
        BreakpointDetector detector = new BreakpointDetector(BASELINE);
        BreakpointDetector.Breakpoint breakpoint = null;
        for (int i = 0; i < 3; i++) breakpoint = detector.onWindow(window(40 + i * W, 20, 110L, 6, 20, true, false));

        assertThat(breakpoint.criteria()).isEqualTo(Set.of(Criterion.REFUSALS));
        assertThat(breakpoint.refusalPercent()).isEqualTo(6.0);
        assertThat(breakpoint.message()).contains("6 % de refus");
    }

    @Test
    void throughputShortfall_countsOnlyWhileTheRampRises_andThePoolCanGrow() {
        BreakpointDetector rising = new BreakpointDetector(BASELINE);
        BreakpointDetector.Breakpoint breakpoint = null;
        for (int i = 0; i < 3; i++) breakpoint = rising.onWindow(window(40 + i * W, 14, 110L, 0, 20, true, false));
        assertThat(breakpoint.criteria()).isEqualTo(Set.of(Criterion.THROUGHPUT));
        assertThat(breakpoint.message()).contains("débit obtenu 14 req/s pour 20 req/s offertes");

        BreakpointDetector poolFull = new BreakpointDetector(BASELINE);
        BreakpointDetector held = new BreakpointDetector(BASELINE);
        BreakpointDetector poolBehind = new BreakpointDetector(BASELINE);
        for (int i = 0; i < 5; i++) {
            assertThat(poolFull.onWindow(window(40 + i * W, 14, 110L, 0, 20, true, true))).isNull();
            assertThat(held.onWindow(window(40 + i * W, 14, 110L, 0, 20, false, false))).isNull();
            assertThat(poolBehind.onWindow(window(40 + i * W, 14, 110L, 0, 20, true, false, true)))
                    .as("deficit venu du retard du reservoir de LoadPilot").isNull();
        }
        // Une fenetre en retard ne casse pas la serie : la rupture se confirme sur les fenetres jugees.
        BreakpointDetector mixed = new BreakpointDetector(BASELINE);
        assertThat(mixed.onWindow(window(40, 14, 110L, 0, 20, true, false))).isNull();
        assertThat(mixed.onWindow(window(45, 14, 110L, 0, 20, true, false, true))).isNull();
        assertThat(mixed.onWindow(window(50, 14, 110L, 0, 20, true, false))).isNull();
        assertThat(mixed.onWindow(window(55, 14, 110L, 0, 20, true, false))).isNotNull();
    }

    @Test
    void loadPilotSaturation_isInconclusive_unlessTheTargetAnomalyStartedBefore() {
        assertThat(BreakpointDetector.inconclusive(50, -1)).as("aucune anomalie").isTrue();
        assertThat(BreakpointDetector.inconclusive(50, 45)).as("anomalie terminee avant la saturation").isFalse();
        assertThat(BreakpointDetector.inconclusive(50, 50)).isFalse();
        assertThat(BreakpointDetector.inconclusive(50, 55)).as("fenetre qui chevauche la saturation").isTrue();
    }
}
