package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loadpilot.backend.exception.ExecutionLimitExceededException;
import org.junit.jupiter.api.Test;

/** Messages de lancement coherents avec la limite affichee dans Parametres > Systeme. */
class RunningExecutionRegistryLimitsTest {

    @Test
    void testLargerThanGlobalLimit_neverLaunchable_notReported_asBusy() {
        RunningExecutionRegistry registry = new RunningExecutionRegistry(200, 10);
        assertThatThrownBy(() -> registry.reserveCapacity(300))
                .isInstanceOf(ExecutionLimitExceededException.class)
                .hasMessage("Ce test demande 300 utilisateurs virtuels, plus que la limite d'un test sur ce serveur (200, limité par la limite globale) : "
                        + "il ne pourra pas être lancé. Réduisez le nombre d'utilisateurs virtuels du scénario.");
    }

    @Test
    void globalLimitReachedByRunningTests_retryLater() {
        RunningExecutionRegistry registry = new RunningExecutionRegistry(200, 10);
        registry.reserveCapacity(150);
        assertThatThrownBy(() -> registry.reserveCapacity(100))
                .hasMessage("Limite globale d'utilisateurs virtuels atteinte : 150 sur 200 déjà utilisés par les tests en cours, "
                        + "ce test en demande 100. Réessayez quand un test en cours sera terminé.");
    }

    @Test
    void stressTest_runsAlone_andBlocksOtherLaunchesUntilItEnds() {
        RunningExecutionRegistry registry = new RunningExecutionRegistry(500, 10);
        registry.reserveCapacity(200, true);
        java.util.UUID id = java.util.UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThat(registry.registerReserved(id, 200, true).isStressMode()).isTrue();

        assertThatThrownBy(() -> registry.reserveCapacity(1)).hasMessage(RunningExecutionRegistry.STRESS_RUNNING);
        assertThatThrownBy(() -> registry.reserveCapacity(200, true)).hasMessage(RunningExecutionRegistry.STRESS_RUNNING);

        registry.unregister(id);
        registry.reserveCapacity(1);
    }

    @Test
    void stressTest_isRefused_whileAnotherExecutionRuns() {
        RunningExecutionRegistry registry = new RunningExecutionRegistry(500, 10);
        registry.reserveCapacity(5);
        assertThatThrownBy(() -> registry.reserveCapacity(200, true))
                .hasMessage("Un test de stress tourne seul sur le serveur : 1 exécution(s) en cours. Réessayez quand elles seront terminées.");

        registry.releaseReservationWithoutRegistering(5);
        registry.reserveCapacity(200, true);
        // Reservation abandonnee (lancement en echec) : la place est liberee.
        registry.releaseReservationWithoutRegistering(200, true);
        registry.reserveCapacity(1);
    }

    @Test
    void tooManyConcurrentTests() {
        RunningExecutionRegistry registry = new RunningExecutionRegistry(200, 1);
        registry.reserveCapacity(10);
        assertThatThrownBy(() -> registry.reserveCapacity(10))
                .hasMessage("Nombre maximal de tests en même temps atteint (1). Réessayez quand un test en cours sera terminé.");
    }
}
