package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.enums.RequestDetailMode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Politique de conservation du detail des requetes : quotas d'echecs, echantillon, plafond memoire. */
class RequestDetailRecorderTest {

    private static final long LARGE = 50L * 1024 * 1024;

    @Test
    void failures_keepAtMost20PerType_andARareLateErrorIsNotLost() {
        RequestDetailRecorder recorder = new RequestDetailRecorder(RequestDetailMode.FAILURES, LARGE);
        int kept500 = 0;
        for (int i = 0; i < 90; i++) {
            if (recorder.acceptFailure(1, RequestDetailRecorder.failureType(500, null), 1000)) kept500++;
        }
        assertThat(kept500).isEqualTo(RequestDetailRecorder.FAILURES_PER_TYPE);
        // Erreur differente, tres tardive : toujours conservee.
        assertThat(recorder.acceptFailure(1, RequestDetailRecorder.failureType(409, null), 1000)).isTrue();
        assertThat(recorder.acceptFailure(1, RequestDetailRecorder.failureType(null, "Delai d'attente depasse (10s)"), 1000)).isTrue();
    }

    @Test
    void failures_atMost100PerStep_otherStepsUnaffected() {
        RequestDetailRecorder recorder = new RequestDetailRecorder(RequestDetailMode.FAILURES, LARGE);
        int kept = 0;
        for (int code = 400; code < 420; code++) {
            for (int i = 0; i < 20; i++) {
                if (recorder.acceptFailure(2, "HTTP " + code, 100)) kept++;
            }
        }
        assertThat(kept).isEqualTo(RequestDetailRecorder.FAILURES_PER_STEP);
        assertThat(recorder.acceptFailure(3, "HTTP 500", 100)).isTrue();
    }

    @Test
    void sample_first3IterationsOnly_andOnlyInFailuresAndSampleMode() {
        RequestDetailRecorder sample = new RequestDetailRecorder(RequestDetailMode.FAILURES_AND_SAMPLE, LARGE);
        assertThat(sample.wantsSample(0)).isTrue();
        assertThat(sample.wantsSample(2)).isTrue();
        assertThat(sample.wantsSample(3)).isFalse();
        assertThat(new RequestDetailRecorder(RequestDetailMode.FAILURES, LARGE).wantsSample(0)).isFalse();
        RequestDetailRecorder none = new RequestDetailRecorder(RequestDetailMode.NONE, LARGE);
        assertThat(none.enabled()).isFalse();
        assertThat(none.wantsFailure(1, "HTTP 500")).isFalse();
    }

    @Test
    void memoryCap_stopsTheSample_butFailuresAreStillRecorded() {
        RequestDetailRecorder recorder = new RequestDetailRecorder(RequestDetailMode.FAILURES_AND_SAMPLE, 10_000);
        assertThat(recorder.acceptSample(6_000)).isTrue();
        assertThat(recorder.acceptSample(6_000)).isFalse();
        assertThat(recorder.sampleCapReached()).isTrue();
        assertThat(recorder.wantsSample(0)).as("echantillon arrete").isFalse();
        assertThat(recorder.acceptFailure(1, "HTTP 500", 6_000)).as("echecs toujours enregistres").isTrue();
    }

    @Test
    void sensitiveVariables_areCollectedForMasking() {
        assertThat(RequestDetailRecorder.sensitiveValues(Map.of("password", "s3cret", "authToken", "TOKEN-1", "amount", "150")))
                .containsExactlyInAnyOrder("s3cret", "TOKEN-1");
    }

    @Test
    void cappedBody_keeps10KbAndReportsTheRealSize() {
        String big = "x".repeat(30_000);
        CappedBody body = CappedBody.of(big, RequestDetailRecorder.BODY_LIMIT_BYTES);
        assertThat(body.truncated()).isTrue();
        assertThat(body.totalBytes()).isEqualTo(30_000);
        assertThat(body.text()).hasSize(RequestDetailRecorder.BODY_LIMIT_BYTES);
        assertThat(CappedBody.of("{\"ok\":true}", RequestDetailRecorder.BODY_LIMIT_BYTES).truncated()).isFalse();
    }
}
