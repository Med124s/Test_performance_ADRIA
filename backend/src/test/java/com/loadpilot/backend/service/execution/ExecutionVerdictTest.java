package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regle du statut final et raison affichee sous le badge (sans base de donnees). */
class ExecutionVerdictTest {

    private static StepOutcome ok(long ms) {
        return new StepOutcome(null, 200, ms, true, null, Instant.now(), 0, 0, "/x");
    }

    private static StepOutcome ko(Integer http, long ms, String error) {
        return new StepOutcome(null, http, ms, false, error, Instant.now(), 0, 0, "/x");
    }

    private static ScenarioExecutionOutcome outcome(List<StepOutcome> all) {
        boolean anyFailure = all.stream().anyMatch(o -> !o.success());
        return new ScenarioExecutionOutcome(all, anyFailure || all.isEmpty() ? ExecutionStatus.FAILED : ExecutionStatus.SUCCESS,
                anyFailure ? "HTTP 500" : null);
    }

    private static List<StepOutcome> mix(int okCount, int koCount, long okMs) {
        List<StepOutcome> all = new ArrayList<>();
        for (int i = 0; i < okCount; i++) all.add(ok(okMs));
        for (int i = 0; i < koCount; i++) all.add(ko(500, okMs, "HTTP 500"));
        return all;
    }

    @Test
    void errorRateThreshold() {
        // 1 erreur sur 200 = 0,5 % <= 1 % : reussie, meme avec une erreur.
        assertThat(ExecutionVerdict.decide(outcome(mix(199, 1, 50)), false, null, 1.0, null).status()).isEqualTo(ExecutionStatus.SUCCESS);
        // 5 sur 200 = 2,5 % > 1 % : seuils depasses, message explicite.
        ExecutionVerdict.Result exceeded = ExecutionVerdict.decide(outcome(mix(195, 5, 50)), false, null, 1.0, null);
        assertThat(exceeded.status()).isEqualTo(ExecutionStatus.THRESHOLDS_EXCEEDED);
        assertThat(exceeded.errorMessage()).isEqualTo("Seuils dépassés : taux d'erreurs 2,5 % > 1 %.");
        // Exactement au maximum : respecte.
        assertThat(ExecutionVerdict.decide(outcome(mix(98, 2, 50)), false, null, 2.0, null).status()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void p95Threshold_sameNearestRankAsTheReport() {
        List<StepOutcome> all = new ArrayList<>();
        for (int i = 1; i <= 100; i++) all.add(ok(i * 10L)); // P95 = 950 ms
        assertThat(ExecutionVerdict.decide(outcome(all), false, null, 1.0, 950L).status()).isEqualTo(ExecutionStatus.SUCCESS);
        ExecutionVerdict.Result slow = ExecutionVerdict.decide(outcome(all), false, null, 1.0, 900L);
        assertThat(slow.status()).isEqualTo(ExecutionStatus.THRESHOLDS_EXCEEDED);
        assertThat(slow.errorMessage()).isEqualTo("Seuils dépassés : P95 950 ms > 900 ms.");
        // Les deux seuils depasses : les deux dans le message.
        List<StepOutcome> both = new ArrayList<>(all);
        for (int i = 0; i < 10; i++) both.add(ko(500, 2000, "HTTP 500"));
        // Separateur des milliers francais : espace insecable (fine ou non selon la JVM).
        assertThat(ExecutionVerdict.decide(outcome(both), false, null, 1.0, 900L).errorMessage())
                .matches("Seuils dépassés : taux d'erreurs 9,1 % > 1 % ; P95 2[\\s\\u00A0\\u202F]000 ms > 900 ms\\.");
    }

    @Test
    void failedOnlyWhenTheTestCouldNotRun() {
        // Authentification refusee : aucune requete jouee.
        ScenarioExecutionOutcome auth = new ScenarioExecutionOutcome(List.of(), ExecutionStatus.FAILED, "Authentification : 401");
        assertThat(ExecutionVerdict.decide(auth, false, null, 1.0, null))
                .isEqualTo(new ExecutionVerdict.Result(ExecutionStatus.FAILED, "Authentification : 401"));
        // Aucune reponse HTTP : application injoignable.
        ExecutionVerdict.Result down = ExecutionVerdict.decide(outcome(List.of(ko(null, 3, "Connection refused"), ko(null, 2, "Connection refused"))),
                false, null, 1.0, null);
        assertThat(down.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(down.errorMessage()).startsWith(ExecutionVerdict.UNREACHABLE).contains("Connection refused");
        // Toutes les requetes en erreur HTTP, mais l'application a repondu : test alle au bout.
        assertThat(ExecutionVerdict.decide(outcome(mix(0, 10, 5)), false, null, 1.0, null).status()).isEqualTo(ExecutionStatus.THRESHOLDS_EXCEEDED);
    }

    @Test
    void cancelledAndLegacyExecutions() {
        assertThat(ExecutionVerdict.decide(outcome(mix(10, 0, 5)), true, "Annulée", 1.0, null).status()).isEqualTo(ExecutionStatus.CANCELLED);
        // Sans seuils (execution preparee avant les seuils) : statut du moteur garde.
        assertThat(ExecutionVerdict.decide(outcome(mix(199, 1, 5)), false, null, null, null).status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(ExecutionVerdict.decide(new ScenarioExecutionOutcome(List.of(), ExecutionStatus.SUCCESS, null), false, null, null, null).status())
                .isEqualTo(ExecutionStatus.SUCCESS);
    }

    private static final BreakpointDetector.Breakpoint BREAKPOINT = new BreakpointDetector.Breakpoint(
            java.util.Set.of(BreakpointDetector.Criterion.RESPONSE_TIME), 45, 60, 12, 20.0, 19.5, 400L, 100L, 0.0,
            "Point de rupture vers 20 req/s offertes et 12 utilisateur(s) virtuel(s), à 45 s du début : P95 de 400 ms.");

    @Test
    void stressVerdict_followsTheBreakpoint_notTheScenarioThresholds() {
        // 10 % d'erreurs : au-dela de tout seuil, mais sans objet en mode stress.
        ScenarioExecutionOutcome ran = outcome(mix(90, 10, 50));

        ExecutionVerdict.Result rupture = ExecutionVerdict.decideStress(ran, false, null,
                new StressResult(com.loadpilot.backend.enums.StressOutcome.BREAKPOINT, BREAKPOINT, null, 30, 35.0, BREAKPOINT.message()));
        assertThat(rupture).isEqualTo(new ExecutionVerdict.Result(ExecutionStatus.THRESHOLDS_EXCEEDED, BREAKPOINT.message()));

        StressResult noBreakpoint = new StressResult(com.loadpilot.backend.enums.StressOutcome.NO_BREAKPOINT, null,
                StressRamp.Limit.TARGET_RPS, 200, 500.0, StressResult.noBreakpointMessage(200, 500.0, StressRamp.Limit.TARGET_RPS));
        assertThat(ExecutionVerdict.decideStress(ran, false, null, noBreakpoint))
                .isEqualTo(new ExecutionVerdict.Result(ExecutionStatus.SUCCESS, null));
        assertThat(noBreakpoint.message())
                .isEqualTo("Aucune rupture détectée jusqu'à 200 VU / 500 req/s offertes (limite de LoadPilot atteinte : débit cible maximal).");

        ExecutionVerdict.Result baseline = ExecutionVerdict.decideStress(ran, false, null,
                new StressResult(com.loadpilot.backend.enums.StressOutcome.BASELINE_UNUSABLE, null, null, 3, 2.0, "Baseline impossible : 12 réponse(s)"));
        assertThat(baseline).isEqualTo(new ExecutionVerdict.Result(ExecutionStatus.FAILED, "Baseline impossible : 12 réponse(s)"));

        // Non concluant : annulation automatique avec son motif.
        String inconclusive = BreakpointWatcher.INCONCLUSIVE_PREFIX + " : CPU de LoadPilot à 95 %.";
        assertThat(ExecutionVerdict.decideStress(ran, true, inconclusive, new StressResult(
                com.loadpilot.backend.enums.StressOutcome.INCONCLUSIVE, null, null, 50, 80.0, inconclusive)))
                .isEqualTo(new ExecutionVerdict.Result(ExecutionStatus.CANCELLED, inconclusive));
    }

    @Test
    void stressVerdict_testThatCouldNotRun_isFailed() {
        ScenarioExecutionOutcome down = outcome(List.of(ko(null, 3, "Connection refused")));
        assertThat(ExecutionVerdict.decideStress(down, false, null, null).errorMessage()).startsWith(ExecutionVerdict.UNREACHABLE);
        assertThat(ExecutionVerdict.decideStress(outcome(mix(5, 0, 5)), false, null,
                new StressResult(null, null, null, 1, 2.0, null)))
                .isEqualTo(new ExecutionVerdict.Result(ExecutionStatus.FAILED, ExecutionVerdict.STRESS_WITHOUT_RESULT));
    }

    @Test
    void stressStatusReasons() {
        Execution rupture = Execution.builder().status(ExecutionStatus.THRESHOLDS_EXCEEDED).successfulSteps(90).failedSteps(10)
                .stressOutcome(com.loadpilot.backend.enums.StressOutcome.BREAKPOINT).breakpointOfferedRps(182.0).build();
        assertThat(ExecutionStatusReason.of(rupture)).isEqualTo("Point de rupture : 182 req/s");

        Execution none = Execution.builder().status(ExecutionStatus.SUCCESS)
                .stressOutcome(com.loadpilot.backend.enums.StressOutcome.NO_BREAKPOINT).stressPeakOfferedRps(500.0).build();
        assertThat(ExecutionStatusReason.of(none)).isEqualTo("Aucune rupture jusqu’à 500 req/s");

        Execution baseline = Execution.builder().status(ExecutionStatus.FAILED).successfulSteps(12).failedSteps(0)
                .stressOutcome(com.loadpilot.backend.enums.StressOutcome.BASELINE_UNUSABLE).errorMessage("Baseline impossible : 12").build();
        assertThat(ExecutionStatusReason.of(baseline)).isEqualTo("Baseline impossible");

        Execution inconclusive = Execution.builder().status(ExecutionStatus.CANCELLED)
                .errorMessage(BreakpointWatcher.INCONCLUSIVE_PREFIX + " : CPU de LoadPilot à 95 %.").build();
        assertThat(ExecutionStatusReason.of(inconclusive)).isEqualTo("Non concluant : LoadPilot saturé");
    }

    @Test
    void statusReasons() {
        Execution e = Execution.builder().status(ExecutionStatus.THRESHOLDS_EXCEEDED).successfulSteps(195).failedSteps(5)
                .maxErrorRatePercent(1.0).build();
        assertThat(ExecutionStatusReason.of(e)).isEqualTo("Erreurs : 2,5 %");
        e.setFailedSteps(0);
        e.setMaxP95Ms(100L);
        assertThat(ExecutionStatusReason.of(e)).isEqualTo("P95 > seuil");

        Execution failed = Execution.builder().status(ExecutionStatus.FAILED).successfulSteps(0).failedSteps(0)
                .maxErrorRatePercent(1.0).errorMessage("Authentification : identifiants refusés").build();
        assertThat(ExecutionStatusReason.of(failed)).isEqualTo("Authentification échouée");
        failed.setErrorMessage(ExecutionVerdict.UNREACHABLE + " (Connection refused).");
        assertThat(ExecutionStatusReason.of(failed)).isEqualTo("Application injoignable");
        failed.setErrorMessage("Backend restarted while execution was running");
        assertThat(ExecutionStatusReason.of(failed)).isEqualTo("Interrompue (redémarrage du serveur)");
        failed.setErrorMessage("NullPointerException");
        assertThat(ExecutionStatusReason.of(failed)).isEqualTo("Erreur interne");

        // Ancienne execution (sans seuils) echouee sur des erreurs : le taux d'erreurs.
        Execution legacy = Execution.builder().status(ExecutionStatus.FAILED).successfulSteps(78).failedSteps(2).errorMessage("HTTP 500").build();
        assertThat(ExecutionStatusReason.of(legacy)).isEqualTo("Erreurs : 2,5 %");

        Execution cancelled = Execution.builder().status(ExecutionStatus.CANCELLED).cancelledBy("admin-test").build();
        assertThat(ExecutionStatusReason.of(cancelled)).isEqualTo("Annulée par admin-test");
        cancelled.setCancelledBy(null);
        cancelled.setErrorMessage("Execution annulee automatiquement : pause trop longue.");
        assertThat(ExecutionStatusReason.of(cancelled)).isEqualTo("Annulée : pause trop longue");

        assertThat(ExecutionStatusReason.of(Execution.builder().status(ExecutionStatus.SUCCESS).build())).isNull();
        assertThat(ExecutionStatusReason.of(Execution.builder().status(ExecutionStatus.RUNNING).build())).isNull();
    }
}
