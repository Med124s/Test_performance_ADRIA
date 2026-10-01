package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.execution.CancellationAttempt;
import com.loadpilot.backend.service.execution.CancellationOutcome;
import com.loadpilot.backend.service.execution.CsvDataSource;
import com.loadpilot.backend.service.execution.LoadTestSpec;
import com.loadpilot.backend.service.execution.PreparedExecution;
import com.loadpilot.backend.service.execution.ScenarioExecutionOutcome;
import com.loadpilot.backend.service.execution.StepExecutionSpec;
import com.loadpilot.backend.service.execution.StepOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isole les DEUX SEULES portions transactionnelles courtes de
 * l'orchestration d'une execution (voir ExecutionServiceImpl.execute) : la
 * preparation/creation (avant le vrai travail HTTP) et la finalisation
 * (apres). Le vrai travail HTTP (potentiellement long : plusieurs requetes
 * reelles) se deroule ENTRE ces deux appels, HORS de toute transaction -
 * jamais de connexion DB retenue pendant l'attente reseau (voir Phase 9,
 * consigne explicite sur les transactions).
 *
 * Bean separe (et non des methodes @Transactional sur ExecutionServiceImpl
 * lui-meme) : un appel this.methode() depuis la meme classe ne passe pas
 * par le proxy Spring et @Transactional serait alors ignore.
 */
@Service
@RequiredArgsConstructor
public class ExecutionTransactionHelper {

    private static final Sort STEP_ORDER = Sort.by(Sort.Direction.ASC, "order").and(Sort.by(Sort.Direction.ASC, "id"));

    private final ScenarioRepository scenarioRepository;
    private final StepRepository stepRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository executionStepResultRepository;
    private final com.loadpilot.backend.repository.AppUserRepository appUserRepository;

    /** Meme garde-fou que ScenarioServiceImpl (P0-B) - une surcharge de
     * virtualUsers propre a une Execution ne doit pas pouvoir contourner
     * cette limite de deploiement (sinon elle ne s'appliquerait qu'a la
     * configuration enregistree du Scenario, jamais au lancement reel). */
    @org.springframework.beans.factory.annotation.Value("${app.execution.max-virtual-users-per-execution}")
    private int maxVirtualUsersPerExecution;

    /**
     * Cree reellement l'Execution en base avec le statut QUEUED (P0-A) -
     * plus RUNNING directement : le vrai travail (potentiellement long, VUs
     * concurrents) n'a pas encore commence, voir ExecutionServiceImpl qui
     * planifie ensuite la tache asynchrone. Les parametres de charge du
     * Scenario sont figes ici sur l'Execution (voir Execution.virtualUsers
     * et suivants) - jamais recalcules depuis le Scenario courant ensuite.
     *
     * "triggeredByAppUserId" (P1-B) : reconstruit ICI, DANS cette
     * transaction, une reference geree fraiche (getReferenceById - meme
     * idiome que stepRepository.getReferenceById plus bas) - jamais une
     * entite AppUser recue en parametre depuis un appelant qui l'aurait
     * chargee dans une AUTRE transaction (voir PreparedExecution).
     */
    @Transactional
    public PreparedExecution prepareAndStart(UUID scenarioId, UUID triggeredByAppUserId, UUID scheduleId,
            com.loadpilot.backend.dto.request.ExecutionRequest overrides) {
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + scenarioId));

        // Passage produit reel (2026-10-01) — surcharges REELLEMENT propres a
        // CETTE Execution (voir ExecutionRequest) : null = valeur du
        // Scenario (comportement historique inchange), non-null = valeur
        // utilisee UNIQUEMENT ici, jamais ecrite sur le Scenario (aucun
        // scenarioRepository.save(...) dans cette methode).
        int effectiveVirtualUsers = overrides != null && overrides.virtualUsers() != null
                ? overrides.virtualUsers() : scenario.getVirtualUsers();
        if (effectiveVirtualUsers > maxVirtualUsersPerExecution) {
            throw new com.loadpilot.backend.exception.LoadConfigurationException(
                    "Le nombre d'utilisateurs virtuels (" + effectiveVirtualUsers
                            + ") depasse la limite configuree pour ce deploiement (" + maxVirtualUsersPerExecution + ").");
        }
        int effectiveRampUpSeconds = overrides != null && overrides.rampUpSeconds() != null
                ? overrides.rampUpSeconds() : scenario.getRampUpSeconds();
        Integer effectiveDurationSeconds = overrides != null && overrides.durationSeconds() != null
                ? overrides.durationSeconds() : scenario.getDurationSeconds();
        Integer effectiveIterations = overrides != null && overrides.iterations() != null
                ? overrides.iterations() : scenario.getIterations();
        int effectiveThinkTimeMs = overrides != null && overrides.thinkTimeMs() != null
                ? overrides.thinkTimeMs() : scenario.getThinkTimeMs();
        Integer effectiveTargetRps = overrides != null && overrides.targetRps() != null
                ? overrides.targetRps() : scenario.getTargetRps();
        com.loadpilot.backend.enums.StopMode effectiveStopMode = overrides != null && overrides.stopMode() != null
                ? overrides.stopMode() : scenario.getStopMode();

        // Passage produit reel (2026-09-30) : une etape INACTIVE (voir
        // StepStatus) est desormais REELLEMENT ignoree - jamais executee,
        // jamais comptee dans totalSteps. Avant ce changement, StepStatus
        // etait une colonne reelle mais jamais lue par le moteur (toute
        // etape s'executait toujours, quel que soit son statut).
        List<Step> allSteps = stepRepository.findByScenarioId(scenarioId, STEP_ORDER);
        List<Step> steps = allSteps.stream()
                .filter(s -> s.getStatus() == com.loadpilot.backend.enums.StepStatus.ACTIVE)
                .toList();
        if (steps.isEmpty()) {
            throw new ConflictException("Le scenario ne possede aucune etape active a executer.");
        }

        // Acces a l'Application au sein de cette meme transaction courte -
        // jamais transporte tel quel hors de cette methode (voir StepExecutionSpec).
        String applicationBaseUrl = scenario.getApplication().getUrl();

        AppUser triggeredByRef = triggeredByAppUserId != null
                ? appUserRepository.getReferenceById(triggeredByAppUserId) : null;

        Execution execution = Execution.builder()
                .scenario(scenario)
                .startedAt(Instant.now())
                .status(ExecutionStatus.QUEUED)
                .virtualUsers(effectiveVirtualUsers)
                .rampUpSeconds(effectiveRampUpSeconds)
                .durationSeconds(effectiveDurationSeconds)
                .iterations(effectiveIterations)
                .stopMode(effectiveStopMode)
                .totalSteps(steps.size())
                .successfulSteps(0)
                .failedSteps(0)
                .triggeredBy(triggeredByRef)
                .build();
        Execution saved = executionRepository.saveAndFlush(execution);

        List<StepExecutionSpec> specs = steps.stream()
                .map(s -> new StepExecutionSpec(s.getId(), s.getName(), s.getMethod(), s.getUrl(),
                        s.getHeaders(), s.getBody(), s.getExpectedStatus(), s.getThinkTimeMs(),
                        s.getPacingAfterMs(), s.getTimeoutSeconds(), s.getFollowRedirects(),
                        s.getAssertionBodyContains(), s.getCaptureVariableName(), s.getCaptureJsonPath()))
                .toList();

        LoadTestSpec loadSpec = new LoadTestSpec(effectiveVirtualUsers, effectiveRampUpSeconds,
                effectiveDurationSeconds, effectiveIterations, effectiveThinkTimeMs,
                effectiveTargetRps, effectiveStopMode);

        // P1-Q Etape B - parse ICI (transaction courte, Scenario encore
        // gere) le CSV data source eventuel du Scenario - jamais reparse a
        // chaque VU/iteration (cout constant, une seule fois par lancement).
        List<Map<String, String>> vuVariableRows = CsvDataSource.parse(scenario.getCsvData());

        return new PreparedExecution(saved.getId(), specs, applicationBaseUrl, loadSpec, vuVariableRows,
                scenario.getName(), triggeredByAppUserId, scheduleId);
    }

    /**
     * Transition reelle QUEUED -> RUNNING, juste avant que le moteur ne
     * commence reellement a envoyer des requetes (voir ExecutionServiceImpl).
     *
     * P0-B : verrou pessimiste + retour booleen (avant : void, silencieux).
     * PROBLEME identifie : un cancel() concurrent peut avoir deja fait
     * transiter cette Execution QUEUED -> CANCELLED entre la creation de la
     * tache asynchrone et cet appel - sans le savoir, l'ancien code
     * poursuivait quand meme vers le moteur reel, envoyant de VRAIES
     * requetes HTTP pour une execution que l'utilisateur croyait deja
     * annulee avant meme son demarrage. Le retour boolean permet a
     * ExecutionServiceImpl.runAsync de ne JAMAIS invoquer le moteur si la
     * transition a echoue (statut deja different de QUEUED).
     */
    @Transactional
    public boolean markRunning(UUID executionId) {
        Execution execution = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));
        if (execution.getStatus() == ExecutionStatus.QUEUED) {
            execution.setStatus(ExecutionStatus.RUNNING);
            executionRepository.saveAndFlush(execution);
            return true;
        }
        return false;
    }

    /**
     * "wasCancelled" (P0-A) force reellement le statut final a CANCELLED,
     * quel que soit outcome.finalStatus() (toujours SUCCESS/FAILED cote
     * engine, voir ScenarioExecutionOutcome) - une annulation demandee
     * pendant l'execution reste une annulation meme si, par exemple, le
     * dernier utilisateur virtuel a eu le temps de terminer proprement.
     *
     * P0-B : garde ajoutee - si le statut courant (relu SOUS VERROU, jamais
     * l'instance potentiellement perimee passee en memoire) n'est deja plus
     * QUEUED/RUNNING, cette Execution a deja ete finalisee par un autre
     * chemin concurrent (ex: attemptCancellation a gagne la course pendant
     * que cette tache demarrait a peine, voir markRunning) - ne JAMAIS
     * ecraser un statut terminal deja reellement atteint.
     */
    @Transactional
    public Execution finalizeExecution(UUID executionId, ScenarioExecutionOutcome outcome, boolean wasCancelled) {
        Execution execution = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));

        if (execution.getStatus() != ExecutionStatus.RUNNING && execution.getStatus() != ExecutionStatus.QUEUED) {
            return execution;
        }

        for (StepOutcome stepOutcome : outcome.stepOutcomes()) {
            // getReferenceById : proxy non initialise, suffisant pour renseigner
            // la FK sans requete supplementaire (aucun getter du Step appele ici).
            Step stepRef = stepRepository.getReferenceById(stepOutcome.stepId());
            ExecutionStepResult result = ExecutionStepResult.builder()
                    .execution(execution)
                    .step(stepRef)
                    .httpStatus(stepOutcome.httpStatus())
                    .responseTime(stepOutcome.responseTimeMs())
                    .success(stepOutcome.success())
                    .error(stepOutcome.error())
                    .timestamp(stepOutcome.timestamp())
                    .build();
            executionStepResultRepository.save(result);
        }

        long successCount = outcome.stepOutcomes().stream().filter(StepOutcome::success).count();
        long failCount = outcome.stepOutcomes().size() - successCount;

        Instant finishedAt = Instant.now();
        execution.setSuccessfulSteps((int) successCount);
        execution.setFailedSteps((int) failCount);
        execution.setStatus(wasCancelled ? ExecutionStatus.CANCELLED : outcome.finalStatus());
        execution.setErrorMessage(wasCancelled ? "Execution annulee par l'utilisateur." : outcome.errorMessage());
        execution.setFinishedAt(finishedAt);
        execution.setDuration(Duration.between(execution.getStartedAt(), finishedAt).toMillis());

        return executionRepository.saveAndFlush(execution);
    }

    /**
     * P0-B — remplace l'ancien cancelQueued() (qui ecrasait aveuglement le
     * statut sans jamais revalider qu'il etait toujours QUEUED au moment
     * reel de l'ecriture).
     *
     * PROBLEME : ExecutionServiceImpl.cancel() lisait le statut, PUIS
     * appelait une methode transactionnelle separee qui reecrivait
     * CANCELLED sans re-verifier - entre les deux, l'execution pouvait deja
     * avoir reellement demarre (markRunning) voire deja terminer
     * naturellement (finalizeExecution), auquel cas un SUCCESS/FAILED deja
     * atteint etait incorrectement ecrase par CANCELLED (course "cancel +
     * completion naturelle").
     * OPTIONS : (a) laisser tel quel (risque reel demontre) ; (b) ajouter un
     * simple garde de statut sans verrou (reduit mais n'elimine pas la
     * fenetre de course) ; (c) une lecture + decision + ecriture
     * conditionnelle, le TOUT dans UNE transaction avec verrou pessimiste
     * (SELECT ... FOR UPDATE) sur cette ligne, serialise avec markRunning/
     * finalizeExecution qui prennent le MEME verrou.
     * CHOIX : (c) - verrou sur une seule ligne d'une seule base (jamais
     * distribue), complexite minimale (une annotation @Lock), elimine
     * completement la course plutot que de la reduire.
     *
     * Retourne le resultat REEL constate (jamais suppose) : le statut a pu
     * changer entre le moment ou l'appelant a decide d'annuler et celui ou
     * cette methode a pu acquerir le verrou.
     */
    @Transactional
    public CancellationAttempt attemptCancellation(UUID executionId) {
        // JOIN FETCH necessaire ici (contrairement a markRunning/
        // finalizeExecution) : le resultat est mappe en reponse HTTP par
        // l'appelant APRES le retour de cette methode transactionnelle.
        Execution execution = executionRepository.findByIdWithScenarioAndApplicationForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));

        if (execution.getStatus() == ExecutionStatus.QUEUED) {
            Instant finishedAt = Instant.now();
            execution.setStatus(ExecutionStatus.CANCELLED);
            execution.setErrorMessage("Execution annulee par l'utilisateur avant son demarrage.");
            execution.setFinishedAt(finishedAt);
            execution.setDuration(Duration.between(execution.getStartedAt(), finishedAt).toMillis());
            Execution saved = executionRepository.saveAndFlush(execution);
            return new CancellationAttempt(CancellationOutcome.CANCELLED_WHILE_QUEUED, saved);
        }

        if (execution.getStatus() == ExecutionStatus.RUNNING) {
            // Jamais d'ecriture ici : finalizeExecution reste l'UNIQUE
            // ecrivain du statut final d'une execution reellement demarree
            // (voir sa doc) - deux ecrivains concurrents sur la meme
            // transition produiraient exactement la course qu'on elimine ici.
            return new CancellationAttempt(CancellationOutcome.CANCELLATION_REQUESTED_WHILE_RUNNING, execution);
        }

        return new CancellationAttempt(CancellationOutcome.ALREADY_TERMINAL, execution);
    }
}
