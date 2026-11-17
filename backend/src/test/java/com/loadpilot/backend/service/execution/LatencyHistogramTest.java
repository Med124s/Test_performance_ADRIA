package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LatencyHistogramTest {

    @Test
    void everyTime_fallsInAClassWhoseUpperBoundCoversItWithin15Percent() {
        for (long ms = 0; ms <= 20_000; ms++) {
            long upper = LatencyHistogram.upperBoundMs(LatencyHistogram.bucketOf(ms));
            assertThat(upper).as("ms=%d", ms).isGreaterThanOrEqualTo(ms).isLessThanOrEqualTo(Math.max(1, (long) (ms * 1.15)));
        }
    }

    @Test
    void timesAboveTheMaximum_goToTheLastClass() {
        assertThat(LatencyHistogram.bucketOf(LatencyHistogram.MAX_MS * 10)).isEqualTo(LatencyHistogram.BUCKETS - 1);
        assertThat(LatencyHistogram.bucketOf(LatencyHistogram.MAX_MS)).isEqualTo(LatencyHistogram.BUCKETS - 1);
    }

    @Test
    void p95_ofTimesFrom1To100Ms_isCloseTo95() {
        long[] counts = new long[LatencyHistogram.BUCKETS];
        for (long ms = 1; ms <= 100; ms++) counts[LatencyHistogram.bucketOf(ms)]++;

        assertThat(LatencyHistogram.percentile(counts, 95)).isBetween(95L, 109L);
    }

    @Test
    void p95_isDrivenByTheSlowestFivePercent() {
        long[] counts = new long[LatencyHistogram.BUCKETS];
        counts[LatencyHistogram.bucketOf(100)] = 90;
        counts[LatencyHistogram.bucketOf(2_000)] = 10;

        assertThat(LatencyHistogram.percentile(counts, 95)).isBetween(2_000L, 2_300L);
        assertThat(LatencyHistogram.percentile(counts, 50)).isBetween(100L, 115L);
    }

    @Test
    void noTime_noPercentile() {
        assertThat(LatencyHistogram.percentile(new long[LatencyHistogram.BUCKETS], 95)).isNull();
    }
}
