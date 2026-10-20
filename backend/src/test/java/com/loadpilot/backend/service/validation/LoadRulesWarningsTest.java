package com.loadpilot.backend.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Avertissement « beaucoup d'utilisateurs sans debit cible » : jamais un refus, seuil reglable, desactivable. */
class LoadRulesWarningsTest {

    private static LoadLimits limits(int threshold) {
        return new LoadLimits(500, 3600, 10_000, 500, threshold);
    }

    @Test
    void underTheThreshold_noWarning() {
        assertThat(LoadRules.warnings(50, null, limits(50))).isEmpty();
        assertThat(LoadRules.warnings(1, null, limits(50))).isEmpty();
    }

    @Test
    void aboveTheThresholdWithoutTargetRps_warning() {
        assertThat(LoadRules.warnings(51, null, limits(50))).containsExactly(
                "Plus de 50 utilisateurs sans débit cible : le débit n’est pas borné. "
                        + "Fixez un débit cible pour maîtriser la charge envoyée et la mémoire de LoadPilot.");
        // Jamais un refus : les regles de validation ne changent pas.
        assertThat(LoadRules.validate(51, 0, 0, 60, null, null, limits(50))).isEmpty();
    }

    @Test
    void aboveTheThresholdWithTargetRps_noWarning() {
        assertThat(LoadRules.warnings(200, 100, limits(50))).isEmpty();
    }

    @Test
    void thresholdZero_disablesTheWarning_andDefaultIs50() {
        assertThat(LoadRules.warnings(500, null, limits(0))).isEmpty();
        assertThat(new LoadLimits(500, 3600, 10_000, 500).targetRpsAdvisedAboveVirtualUsers()).isEqualTo(50);
        assertThat(LoadRules.warnings(null, null, limits(50))).isEmpty();
    }
}
