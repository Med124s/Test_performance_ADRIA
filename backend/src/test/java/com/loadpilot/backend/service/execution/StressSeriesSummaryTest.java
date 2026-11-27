package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.enums.StressOutcome;
import com.loadpilot.backend.service.execution.StressSeriesSummary.Run;
import com.loadpilot.backend.service.execution.StressSeriesSummary.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class StressSeriesSummaryTest {

    private static Run rupture(double rps) {
        return new Run(StressOutcome.BREAKPOINT, rps, rps + 15, 1);
    }

    private static Run noRupture(double peak) {
        return new Run(StressOutcome.NO_BREAKPOINT, null, peak, 1);
    }

    private static String text(StressSeriesSummary s) {
        return s.message().replaceAll("[\u00A0\u202F]", " ");
    }

    @Test
    void closeRuptures_areConfirmed_withMeanRangeSpreadAndPrecision() {
        StressSeriesSummary s = StressSeriesSummary.of(List.of(rupture(63), rupture(60), rupture(66)));

        assertThat(s.verdict()).isEqualTo(Verdict.CONFIRMED);
        assertThat(s.meanRps()).isEqualTo(63.0);
        assertThat(s.minRps()).isEqualTo(60.0);
        assertThat(s.maxRps()).isEqualTo(66.0);
        assertThat(s.spreadPercent()).isCloseTo(9.52, org.assertj.core.data.Offset.offset(0.01));
        assertThat(s.precisionRps()).isEqualTo(5);
        assertThat(text(s)).isEqualTo("Point de rupture confirmé autour de 63 req/s, entre 60 et 66 req/s sur 3 essais "
                + "(écart de 9,5 %, précision ± 5 req/s).");
    }

    @Test
    void theSameValueOnEveryRun_isConfirmedAtThatValue_notWithinARange() {
        StressSeriesSummary s = StressSeriesSummary.of(List.of(rupture(63), rupture(63), rupture(63)));
        assertThat(s.verdict()).isEqualTo(Verdict.CONFIRMED);
        assertThat(s.spreadPercent()).isZero();
        assertThat(text(s)).isEqualTo("Point de rupture confirmé à 63 req/s sur les 3 essais (même valeur à chaque essai, "
                + "précision ± 5 req/s).");
        assertThat(text(StressSeriesSummary.of(List.of(rupture(63), rupture(63), noRupture(500)))))
                .contains("rupture sur 2 essai(s) sur 3 (à 63 req/s)");
    }

    @Test
    void spreadAboveTwentyPercent_isDispersed() {
        StressSeriesSummary s = StressSeriesSummary.of(List.of(rupture(50), rupture(63), rupture(75)));
        assertThat(s.verdict()).isEqualTo(Verdict.DISPERSED);
        assertThat(text(s)).startsWith("Résultats dispersés : point de rupture non confirmé, entre 50 et 75 req/s sur 3 essais")
                .contains("au-delà de 20 %");
        // 20 % tout juste : confirme.
        assertThat(StressSeriesSummary.of(List.of(rupture(90), rupture(110))).verdict()).isEqualTo(Verdict.CONFIRMED);
    }

    @Test
    void mixedOrSingleRuptures_areNotConfirmed() {
        StressSeriesSummary mixed = StressSeriesSummary.of(List.of(rupture(63), rupture(61), noRupture(500)));
        assertThat(mixed.verdict()).isEqualTo(Verdict.NOT_CONFIRMED);
        assertThat(text(mixed)).isEqualTo("Point de rupture non confirmé : rupture sur 2 essai(s) sur 3 (entre 61 et 63 req/s), "
                + "aucune rupture jusqu'à 500 req/s sur 1 essai.");

        StressSeriesSummary single = StressSeriesSummary.of(List.of(rupture(63)));
        assertThat(single.verdict()).isEqualTo(Verdict.NOT_CONFIRMED);
        assertThat(text(single)).contains("un seul essai concluant (rupture vers 63 req/s, précision ± 5 req/s)");
    }

    @Test
    void inconclusiveRunsAreLeftOut() {
        StressSeriesSummary s = StressSeriesSummary.of(List.of(rupture(63), new Run(StressOutcome.INCONCLUSIVE, null, 80.0, 1),
                rupture(65), new Run(null, null, 30.0, 1)));
        assertThat(s.verdict()).isEqualTo(Verdict.CONFIRMED);
        assertThat(s.conclusiveRuns()).isEqualTo(2);

        assertThat(StressSeriesSummary.of(List.of(new Run(StressOutcome.BASELINE_UNUSABLE, null, 2.0, 1))).verdict())
                .isEqualTo(Verdict.NONE);
    }

    @Test
    void noRuptureOnAnyRun_reportsTheLowestLevelReached() {
        StressSeriesSummary s = StressSeriesSummary.of(List.of(noRupture(500), noRupture(480)));
        assertThat(s.verdict()).isEqualTo(Verdict.NO_BREAKPOINT);
        assertThat(text(s)).isEqualTo("Aucune rupture jusqu'à 480 req/s sur 2 essais.");
    }

    @Test
    void differentRampSteps_areNotComparable() {
        StressSeriesSummary s = StressSeriesSummary.of(List.of(rupture(63), new Run(StressOutcome.BREAKPOINT, 120.0, 150.0, 20)));
        assertThat(s.verdict()).isEqualTo(Verdict.NOT_COMPARABLE);
        assertThat(text(s)).contains("+1, +20 req/s par seconde");
        // Vitesse absente (execution anterieure) : vitesse par defaut.
        assertThat(StressSeriesSummary.of(List.of(rupture(63), new Run(StressOutcome.BREAKPOINT, 64.0, 70.0, null))).verdict())
                .isEqualTo(Verdict.CONFIRMED);
    }
}
