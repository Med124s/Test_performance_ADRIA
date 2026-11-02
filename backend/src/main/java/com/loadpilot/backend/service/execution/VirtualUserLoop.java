package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.service.http.NetworkProfile;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Boucle d'un utilisateur virtuel : demarrage apres son delai de montee en charge, puis iterations jusqu'a l'annulation, la fin de la duree ou le nombre d'iterations. */
final class VirtualUserLoop {

    /** Envoie une etape et renvoie son resultat. */
    @FunctionalInterface
    interface StepSender {
        StepOutcome send(VirtualUserClients clients, StepExecutionSpec step, int stepNumber, VariableContext variables,
                int virtualUser, int iteration, RetryAfterHandler retryAfter);
    }

    /** Reglages communs aux utilisateurs virtuels d'une execution ; pacingGate et deadline peuvent etre null. */
    record Shared(List<StepExecutionSpec> steps, LoadTestSpec loadSpec, RunningExecutionHandle handle, ActiveDeadline deadline,
            PacingGate pacingGate, VariableSources sources, NetworkProfile network, long connectTimeoutSeconds,
            Supplier<VirtualUserCookieJar> cookieJars, StepSender sender) {
    }

    private final Shared shared;
    private final int virtualUser;
    private final long startDelayMs;
    /** Variables propres a cet utilisateur virtuel. */
    private final VariableContext variables;
    private final Consumer<StepOutcome> results;

    VirtualUserLoop(Shared shared, int virtualUser, long startDelayMs, VariableContext variables, Consumer<StepOutcome> results) {
        this.shared = shared;
        this.virtualUser = virtualUser;
        this.startDelayMs = startDelayMs;
        this.variables = variables;
        this.results = results;
    }

    void run() {
        RunningExecutionHandle handle = shared.handle();
        if (!handle.sleepActive(startDelayMs) || handle.isCancelled()) {
            return;
        }

        // Un pot de cookies par utilisateur virtuel, vide au debut de chaque iteration.
        VirtualUserCookieJar cookies = shared.cookieJars().get();
        VirtualUserClients clients = new VirtualUserClients(cookies, shared.network(), shared.connectTimeoutSeconds());

        LiveStats live = handle.liveStats();
        if (live != null) live.virtualUserStarted();
        handle.virtualUserStarted(virtualUser);
        try {
            runIterations(clients, cookies, live);
        } finally {
            if (live != null) live.virtualUserFinished();
            handle.virtualUserFinished(virtualUser);
            clients.shutdownNow();
        }
    }

    private void runIterations(VirtualUserClients clients, VirtualUserCookieJar cookies, LiveStats live) {
        List<StepExecutionSpec> steps = shared.steps();
        LoadTestSpec loadSpec = shared.loadSpec();
        RunningExecutionHandle handle = shared.handle();
        ActiveDeadline deadline = shared.deadline();
        PacingGate pacingGate = shared.pacingGate();
        int iteration = 0;
        RetryAfterHandler retryAfter = new RetryAfterHandler(loadSpec.throttle());
        while (true) {
            if (handle.isCancelled()) break;
            if (!handle.awaitIfPaused()) break;
            if (deadline != null && deadline.reached()) break;
            if (loadSpec.iterations() != null && iteration >= loadSpec.iterations()) break;
            if (deadline == null && loadSpec.iterations() == null && iteration >= 1) break;

            // Nouvelle iteration : captures oubliees, ligne CSV de l'iteration.
            variables.startIteration(shared.sources().rowFor(virtualUser));
            cookies.clear();
            retryAfter.reset();
            for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                StepExecutionSpec step = steps.get(stepIndex);
                if (handle.isCancelled()) break;
                // Debit cible commun a toute l'execution.
                if (pacingGate != null && !pacingGate.acquire()) break;
                if (!handle.awaitIfPaused()) break;

                StepOutcome outcome = shared.sender().send(clients, step, stepIndex + 1, variables, virtualUser, iteration, retryAfter);
                // Requete coupee par l'annulation : ni resultat ni echec.
                if (handle.isCancelled() && outcome.httpStatus() == null) break;
                results.accept(outcome);
                boolean throttled = retryAfter.throttled(outcome);
                if (live != null) live.record(stepIndex, outcome, throttled);
                // 429 respecte : attente du delai demande puis rejeu de la meme etape.
                long waitMs = throttled ? retryAfter.replayDelayMs(outcome, Instant.now()) : -1;
                if (waitMs >= 0) {
                    if (deadline != null) waitMs = Math.min(waitMs, deadline.remainingMs());
                    if (!handle.sleepActive(waitMs) || handle.isCancelled()) break;
                    if (deadline != null && deadline.reached()) break;
                    stepIndex--;
                    continue;
                }
                retryAfter.reset();
                // Pause propre a cette etape.
                if (!handle.isCancelled() && step.thinkTimeMs() != null && step.thinkTimeMs() > 0
                        && !handle.sleepActive(ThinkTime.pick(step.thinkTimeMs(), loadSpec.thinkTimeVariationPercent()))) {
                    break;
                }
                if (!outcome.success()) {
                    break;
                }
            }
            iteration++;
            if (live != null) live.iterationCompleted();
            // Think time apres chaque iteration.
            if (!handle.isCancelled() && loadSpec.thinkTimeMs() > 0
                    && !handle.sleepActive(ThinkTime.pick(loadSpec.thinkTimeMs(), loadSpec.thinkTimeVariationPercent()))) {
                break;
            }
        }
    }
}
