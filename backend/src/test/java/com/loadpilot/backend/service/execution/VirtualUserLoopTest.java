package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.http.NetworkProfile;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Boucle d'un utilisateur virtuel, sans HTTP : l'envoi des etapes est simule (StepSender), ce qui
 * isole l'ordre des etapes, l'arret de l'iteration sur un echec, le nombre d'iterations, les rejeux sur
 * 429 et l'annulation.
 */
class VirtualUserLoopTest {

    private final List<StepExecutionSpec> steps = List.of(step("A"), step("B"), step("C"));
    private final RunningExecutionHandle handle = new RunningExecutionHandle();
    /** Etapes envoyees, dans l'ordre : "nom#iteration". */
    private final List<String> sent = new ArrayList<>();
    private final List<StepOutcome> results = new ArrayList<>();

    private static StepExecutionSpec step(String name) {
        return new StepExecutionSpec(UUID.randomUUID(), name, HttpMethod.GET, "/" + name, null, null, null, null, null, null, null, null, null);
    }

    /** Reponses simulees, consommees dans l'ordre (200 une fois la file videe). */
    private VirtualUserLoop.StepSender sender(Deque<Integer> statuses) {
        return (clients, step, stepNumber, variables, virtualUser, iteration, retryAfter) -> {
            sent.add(step.name() + "#" + iteration);
            int status = statuses.isEmpty() ? 200 : statuses.poll();
            return new StepOutcome(step.stepId(), status, 1, status < 400, status < 400 ? null : "HTTP " + status, Instant.now(),
                    virtualUser, iteration, step.url());
        };
    }

    private void run(LoadTestSpec load, Deque<Integer> statuses) {
        handle.startLiveStats(steps);
        VariableSources sources = new VariableSources(List.of(), CsvDistribution.PER_VIRTUAL_USER, Map.of(), steps);
        VirtualUserLoop.Shared shared = new VirtualUserLoop.Shared(steps, load, handle, null, null, sources, NetworkProfile.direct(), 5,
                VirtualUserCookieJar::new, sender(statuses));
        new VirtualUserLoop(shared, 0, 0, new VariableContext(Map.of(), null), results::add).run();
    }

    private static LoadTestSpec iterations(int n, ThrottlePolicy throttle) {
        return new LoadTestSpec(1, 0, null, n, 0, null, throttle, 0);
    }

    @Test
    void stepsRunInOrder_forEachIteration_andEveryResultIsKept() {
        run(iterations(2, ThrottlePolicy.DEFAULT), new ArrayDeque<>());
        assertThat(sent).containsExactly("A#0", "B#0", "C#0", "A#1", "B#1", "C#1");
        assertThat(results).hasSize(6);
        assertThat(handle.liveStats().totalRequests()).isEqualTo(6);
    }

    @Test
    void withoutDurationOrIterations_exactlyOnePass() {
        run(new LoadTestSpec(1, 0, null, null, 0, null, ThrottlePolicy.DEFAULT, 0), new ArrayDeque<>());
        assertThat(sent).containsExactly("A#0", "B#0", "C#0");
    }

    @Test
    void aFailingStep_stopsItsIterationOnly() {
        run(iterations(2, ThrottlePolicy.DEFAULT), new ArrayDeque<>(List.of(200, 500)));
        assertThat(sent).containsExactly("A#0", "B#0", "A#1", "B#1", "C#1");
    }

    @Test
    void a429_isReplayedUpToMaxRetries_thenCountsAsAFailure() {
        ThrottlePolicy noWait = new ThrottlePolicy(true, 0, 2);
        // A : 429, 429, puis 200 -> rejoue 2 fois, puis continue.
        run(iterations(1, noWait), new ArrayDeque<>(List.of(429, 429, 200)));
        assertThat(sent).containsExactly("A#0", "A#0", "A#0", "B#0", "C#0");
        assertThat(results).hasSize(5);

        sent.clear();
        results.clear();
        // Trois 429 de suite : 2 rejeux, le 3e reste un echec et arrete l'iteration.
        run(iterations(1, noWait), new ArrayDeque<>(List.of(429, 429, 429)));
        assertThat(sent).containsExactly("A#0", "A#0", "A#0");
    }

    @Test
    void throttleDisabled_a429_isAnOrdinaryFailure() {
        run(iterations(1, new ThrottlePolicy(false, 0, 3)), new ArrayDeque<>(List.of(429)));
        assertThat(sent).containsExactly("A#0");
    }

    @Test
    void cancelled_noStepIsSent() {
        handle.requestCancellation();
        run(iterations(5, ThrottlePolicy.DEFAULT), new ArrayDeque<>());
        assertThat(sent).isEmpty();
        assertThat(results).isEmpty();
    }
}
