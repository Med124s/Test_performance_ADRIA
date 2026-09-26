package com.loadpilot.backend.service.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P1-A (prompt section 34) — tests DETERMINISTES avec des valeurs connues,
 * verifiant les VALEURS NUMERIQUES exactes produites par la methode
 * "nearest rank" documentee dans PercentileCalculator - jamais seulement
 * "ca ne plante pas".
 */
class PercentileCalculatorTest {

    private static final List<Long> TEN_VALUES = Arrays.asList(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);

    @Test
    void percentile_onTenEvenlySpacedValues_matchesNearestRankMethodExactly() {
        // rang = ceil(p/100 * 10), valeur = trie[rang-1] - voir Javadoc.
        assertThat(PercentileCalculator.percentile(TEN_VALUES, 50)).isEqualTo(50L);
        assertThat(PercentileCalculator.percentile(TEN_VALUES, 75)).isEqualTo(80L);
        assertThat(PercentileCalculator.percentile(TEN_VALUES, 90)).isEqualTo(90L);
        assertThat(PercentileCalculator.percentile(TEN_VALUES, 95)).isEqualTo(100L);
        assertThat(PercentileCalculator.percentile(TEN_VALUES, 99)).isEqualTo(100L);
    }

    @Test
    void minMaxAverage_onTenEvenlySpacedValues() {
        assertThat(PercentileCalculator.min(TEN_VALUES)).isEqualTo(10L);
        assertThat(PercentileCalculator.max(TEN_VALUES)).isEqualTo(100L);
        assertThat(PercentileCalculator.average(TEN_VALUES)).isEqualTo(55L);
    }

    @Test
    void emptyPopulation_everyMethodReturnsNull_neverAFabricatedValue() {
        List<Long> empty = List.of();
        assertThat(PercentileCalculator.percentile(empty, 50)).isNull();
        assertThat(PercentileCalculator.percentile(empty, 95)).isNull();
        assertThat(PercentileCalculator.percentile(empty, 99)).isNull();
        assertThat(PercentileCalculator.min(empty)).isNull();
        assertThat(PercentileCalculator.max(empty)).isNull();
        assertThat(PercentileCalculator.average(empty)).isNull();
    }

    @Test
    void singleValue_minMaxAverageAndEveryPercentileEqualThatValue() {
        List<Long> single = List.of(10L);
        assertThat(PercentileCalculator.min(single)).isEqualTo(10L);
        assertThat(PercentileCalculator.max(single)).isEqualTo(10L);
        assertThat(PercentileCalculator.average(single)).isEqualTo(10L);
        assertThat(PercentileCalculator.percentile(single, 1)).isEqualTo(10L);
        assertThat(PercentileCalculator.percentile(single, 50)).isEqualTo(10L);
        assertThat(PercentileCalculator.percentile(single, 95)).isEqualTo(10L);
        assertThat(PercentileCalculator.percentile(single, 99)).isEqualTo(10L);
        assertThat(PercentileCalculator.percentile(single, 100)).isEqualTo(10L);
    }

    @Test
    void twoValues_matchesNearestRankMethodExactly() {
        List<Long> two = List.of(10L, 20L);
        assertThat(PercentileCalculator.percentile(two, 50)).isEqualTo(10L);
        assertThat(PercentileCalculator.percentile(two, 95)).isEqualTo(20L);
        assertThat(PercentileCalculator.percentile(two, 99)).isEqualTo(20L);
        assertThat(PercentileCalculator.average(two)).isEqualTo(15L);
    }

    @Test
    void nullValues_areExcludedFromThePopulation_neverTreatedAsZero() {
        List<Long> withNulls = Arrays.asList(10L, null, 20L, null, 30L);
        assertThat(PercentileCalculator.min(withNulls)).isEqualTo(10L);
        assertThat(PercentileCalculator.max(withNulls)).isEqualTo(30L);
        // Moyenne sur (10+20+30)/3 = 20, jamais (10+0+20+0+30)/5 = 12.
        assertThat(PercentileCalculator.average(withNulls)).isEqualTo(20L);
    }

    @Test
    void populationOfOnlyNulls_isTreatedAsEmpty() {
        List<Long> onlyNulls = Arrays.asList(null, null, null);
        assertThat(PercentileCalculator.min(onlyNulls)).isNull();
        assertThat(PercentileCalculator.average(onlyNulls)).isNull();
        assertThat(PercentileCalculator.percentile(onlyNulls, 50)).isNull();
    }

    @Test
    void percentile_isNotSensitiveToInputOrder() {
        List<Long> shuffled = Arrays.asList(70L, 10L, 100L, 40L, 20L, 90L, 60L, 30L, 50L, 80L);
        assertThat(PercentileCalculator.percentile(shuffled, 50)).isEqualTo(50L);
        assertThat(PercentileCalculator.percentile(shuffled, 95)).isEqualTo(100L);
    }

    // ------------------------------------------------------------
    // P1-C — stdDev (ecart-type de POPULATION, jamais d'echantillon).
    // ------------------------------------------------------------

    @Test
    void stdDev_onTenEvenlySpacedValues_matchesHandComputedPopulationVariance() {
        // mean=55, variance=sum((x-55)^2)/10=8250/10=825, sqrt(825)=28.7228... -> 29.
        assertThat(PercentileCalculator.stdDev(TEN_VALUES)).isEqualTo(29L);
    }

    @Test
    void stdDev_twoValues_matchesHandComputedPopulationVariance() {
        // mean=15, variance=((5^2)+(5^2))/2=25, sqrt(25)=5.
        assertThat(PercentileCalculator.stdDev(List.of(10L, 20L))).isEqualTo(5L);
    }

    @Test
    void stdDev_emptyPopulation_returnsNull() {
        assertThat(PercentileCalculator.stdDev(List.of())).isNull();
    }

    @Test
    void stdDev_singleValue_isZero_neverNull() {
        // L'ecart-type d'un singleton est reellement 0 (pas une absence de donnee).
        assertThat(PercentileCalculator.stdDev(List.of(42L))).isEqualTo(0L);
    }

    @Test
    void stdDev_identicalValues_isZero() {
        assertThat(PercentileCalculator.stdDev(List.of(10L, 10L, 10L, 10L))).isEqualTo(0L);
    }

    @Test
    void stdDev_nullValues_areExcludedFromThePopulation() {
        List<Long> withNulls = Arrays.asList(10L, null, 20L, null, 30L);
        // Meme population effective que {10,20,30} : mean=20, variance=(100+0+100)/3=66.67, sqrt=8.165 -> 8.
        assertThat(PercentileCalculator.stdDev(withNulls)).isEqualTo(8L);
    }
}
