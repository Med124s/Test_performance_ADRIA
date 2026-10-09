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
    void tooManyConcurrentTests() {
        RunningExecutionRegistry registry = new RunningExecutionRegistry(200, 1);
        registry.reserveCapacity(10);
        assertThatThrownBy(() -> registry.reserveCapacity(10))
                .hasMessage("Nombre maximal de tests en même temps atteint (1). Réessayez quand un test en cours sera terminé.");
    }
}
