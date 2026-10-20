package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.service.http.NetworkProfile;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Boucle reelle d'UN utilisateur virtuel : demarre apres son delai de ramp-up, puis enchaine des
 * iterations jusqu'a la premiere condition d'arret atteinte (dans cet ordre de verification, avant
 * chaque iteration) : annulation demandee > date limite de duree atteinte > nombre d'iterations
 * demande atteint > (par defaut, sans duree ni iterations configurees) exactement UNE iteration -
 * reproduit alors exactement le comportement historique a une seule passe.
 *
 * POLITIQUE D'ARRET PAR ITERATION : au sein d'UNE iteration, une etape qui echoue arrete cette
 * iteration ; les iterations suivantes de ce VU, et les autres VU, continuent normalement.
 *
 * L'envoi d'une etape (requete, controles, captures, detail, renouvellement du jeton) reste dans
 * HttpClientExecutionEngine, appele via StepSender ; le 429 est decide par RetryAfterHandler.
 */
final class VirtualUserLoop {

    /** Envoie une etape et rend son resultat (voir HttpClientExecutionEngine.executeStepWithRenewal). */
    @FunctionalInterface
    interface StepSender {
        StepOutcome send(VirtualUserClients clients, StepExecutionSpec step, int stepNumber, VariableContext variables,
                int virtualUser, int iteration, RetryAfterHandler retryAfter);
    }

    /**
     * Reglages communs a tous les VU d'une execution. pacingGate : UN SEUL partage par tous les VU
     * (sinon le debit reel serait targetRps x VU), null sans debit cible ; deadline : null sans duree.
     */
    record Shared(List<StepExecutionSpec> steps, LoadTestSpec loadSpec, RunningExecutionHandle handle, ActiveDeadline deadline,
            PacingGate pacingGate, VariableSources sources, NetworkProfile network, long connectTimeoutSeconds,
            Supplier<VirtualUserCookieJar> cookieJars, StepSender sender) {
    }

    private final Shared shared;
    private final int virtualUser;
    private final long startDelayMs;
    /** Contexte de variables PROPRE a ce VU (jamais partage entre threads). */
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

        // Cookies de session : UN pot par VU (jamais partage, sinon les sessions des VUs se
        // melangeraient), commun a ses deux clients. Vide au debut de chaque iteration.
        VirtualUserCookieJar cookies = shared.cookieJars().get();
        VirtualUserClients clients = new VirtualUserClients(cookies, shared.network(), shared.connectTimeoutSeconds());

        // Suivi en direct (compteurs en memoire, aucune ecriture en base) : VU actif jusqu'a sa sortie.
        LiveStats live = handle.liveStats();
        if (live != null) live.virtualUserStarted();
        handle.virtualUserStarted(virtualUser);
        try {
            runIterations(clients, cookies, live);
        } finally {
            if (live != null) live.virtualUserFinished();
            handle.virtualUserFinished(virtualUser);
            // Apres le dernier results.accept (fait par CE thread, dans la boucle), avant le signal de fin
            // du VU (dans HttpClientExecutionEngine.execute) : aucune requete de ce VU n'est plus en cours d'envoi.
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
            // Une pause en cours bloque ici avant toute nouvelle iteration.
            if (!handle.awaitIfPaused()) break;
            if (deadline != null && deadline.reached()) break;
            if (loadSpec.iterations() != null && iteration >= loadSpec.iterations()) break;
            if (deadline == null && loadSpec.iterations() == null && iteration >= 1) break;

            // Nouvelle iteration : captures de l'iteration precedente oubliees, ligne CSV de
            // cette iteration (meme ligne a chaque iteration en distribution "par VU").
            variables.startIteration(shared.sources().rowFor(virtualUser));
            // Meme regle pour les cookies automatiques : chaque iteration repart d'une session vierge.
            cookies.clear();
            retryAfter.reset();
            for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                StepExecutionSpec step = steps.get(stepIndex);
                if (handle.isCancelled()) break;
                // Un seul debit cible pour TOUTE l'execution : chaque requete, de n'importe quel VU,
                // reserve son slot ici avant d'etre envoyee. Sortie immediate si l'attente est
                // interrompue par une annulation (duree d'attente toujours finie, voir PacingGate).
                if (pacingGate != null && !pacingGate.acquire()) break;
                // Pause : aucune nouvelle requete n'est envoyee tant qu'elle dure (les requetes
                // deja en vol recoivent leur reponse normalement).
                if (!handle.awaitIfPaused()) break;

                StepOutcome outcome = shared.sender().send(clients, step, stepIndex + 1, variables, virtualUser, iteration, retryAfter);
                // Requete coupee par l'annulation (aucune reponse recue) : ni resultat ni echec - c'est
                // l'arret demande, pas une erreur de l'application testee.
                if (handle.isCancelled() && outcome.httpStatus() == null) break;
                results.accept(outcome);
                boolean throttled = retryAfter.throttled(outcome);
                if (live != null) live.record(stepIndex, outcome, throttled);
                // 429 respecte : CE VU attend le delai demande (Retry-After, ou delai par defaut), puis
                // rejoue la meme etape, session et captures conservees. Attente en temps actif (pause
                // non decomptee), interrompue par une annulation, jamais au-dela de la fin du test.
                long waitMs = throttled ? retryAfter.replayDelayMs(outcome, Instant.now()) : -1;
                if (waitMs >= 0) {
                    if (deadline != null) waitMs = Math.min(waitMs, deadline.remainingMs());
                    if (!handle.sleepActive(waitMs) || handle.isCancelled()) break;
                    if (deadline != null && deadline.reached()) break;
                    stepIndex--;
                    continue;
                }
                retryAfter.reset();
                // Pause specifique a CETTE etape, en plus du think time de Scenario ci-dessous
                // (jamais a la place) - jamais appliquee apres une annulation ni si non configuree.
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
            // Think time uniquement APRES une iteration, jamais apres une annulation, jamais applique si nul.
            if (!handle.isCancelled() && loadSpec.thinkTimeMs() > 0
                    && !handle.sleepActive(ThinkTime.pick(loadSpec.thinkTimeMs(), loadSpec.thinkTimeVariationPercent()))) {
                break;
            }
        }
    }
}
