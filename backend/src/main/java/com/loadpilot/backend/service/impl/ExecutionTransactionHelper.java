package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionRequestDetail;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.service.execution.ExecutionVerdict;
import com.loadpilot.backend.service.execution.RequestDetail;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.loadpilot.backend.service.auth.ApplicationAuthSupport;
import com.loadpilot.backend.service.execution.CancellationAttempt;
import com.loadpilot.backend.service.execution.CancellationOutcome;
import com.loadpilot.backend.service.execution.CsvDataSource;
import com.loadpilot.backend.service.execution.LoadTestSpec;
import com.loadpilot.backend.service.execution.PreparedExecution;
import com.loadpilot.backend.service.execution.ScenarioExecutionOutcome;
import com.loadpilot.backend.service.execution.StepExecutionSpec;
import com.loadpilot.backend.service.execution.StepOutcome;
import com.loadpilot.backend.service.execution.VariableSources;
import com.loadpilot.backend.service.validation.ScenarioVariables;
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

    public static final String INACTIVE_NOT_RUNNABLE = "Le scénario est inactif, activez-le pour l'exécuter.";

    public static final String DRAFT_NOT_RUNNABLE =
            "Ce scénario est un brouillon : terminez-le dans l'assistant (« Reprendre ») avant de l'exécuter.";

    private final ScenarioRepository scenarioRepository;
    private final StepRepository stepRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository executionStepResultRepository;
    private final com.loadpilot.backend.repository.AppUserRepository appUserRepository;
    private final ApplicationAuthSupport authSupport;
    private final com.loadpilot.backend.repository.ExecutionRequestDetailRepository requestDetailRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.loadpilot.backend.service.monitoring.MonitoringSupport monitoringSupport;

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
    public PreparedExecution prepareAndStart(UUID scenarioId, UUID triggeredByAppUserId, UUID scheduleId) {
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + scenarioId));

        if (scenario.getStatus() == ScenarioStatus.DRAFT) {
            throw new BadRequestException(DRAFT_NOT_RUNNABLE);
        }
        if (scenario.getStatus() == ScenarioStatus.INACTIVE) {
            throw new BadRequestException(INACTIVE_NOT_RUNNABLE);
        }
        if (scenario.isArchived() || scenario.getApplication().isArchived()) {
            throw new ConflictException(scenario.isArchived()
                    ? "Ce scénario est archivé : restaurez-le avant de l'exécuter."
                    : "L'application de ce scénario est archivée : restaurez-la avant de l'exécuter.");
        }

        List<Step> steps = stepRepository.findByScenarioId(scenarioId, STEP_ORDER);
        if (steps.isEmpty()) {
            throw new ConflictException("Le scenario ne possede aucune etape a executer.");
        }

        // Acces a l'Application au sein de cette meme transaction courte -
        // jamais transporte tel quel hors de cette methode (voir StepExecutionSpec).
        String applicationBaseUrl = scenario.getApplication().getUrl();
        // Copie figee de l'authentification de l'Application au lancement.
        ApplicationAuthSpec auth = authSupport.toSpec(scenario.getApplication());

        AppUser triggeredByRef = triggeredByAppUserId != null
                ? appUserRepository.getReferenceById(triggeredByAppUserId) : null;

        Execution execution = Execution.builder()
                .scenario(scenario)
                .startedAt(Instant.now())
                .status(ExecutionStatus.QUEUED)
                .virtualUsers(scenario.getVirtualUsers())
                .rampUpSeconds(scenario.getRampUpSeconds())
                .durationSeconds(scenario.getDurationSeconds())
                .iterations(scenario.getIterations())
                .totalSteps(steps.size())
                .successfulSteps(0)
                .failedSteps(0)
                .triggeredBy(triggeredByRef)
                .scheduleId(scheduleId)
                .requestDetailMode(scenario.getRequestDetailMode())
                .maxErrorRatePercent(scenario.getMaxErrorRatePercent())
                .maxP95Ms(scenario.getMaxP95Ms())
                .build();
        Execution saved = executionRepository.saveAndFlush(execution);

        List<StepExecutionSpec> specs = steps.stream()
                .map(s -> new StepExecutionSpec(s.getId(), s.getName(), s.getMethod(), s.getUrl(),
                        s.getHeaders(), s.getBody(), s.getExpectedStatus(), s.getThinkTimeMs(),
                        s.getTimeoutSeconds(), s.getFollowRedirects(), s.getAssertionBodyContains(),
                        s.getCaptureVariableName(), s.getCaptureJsonPath(), s.getPathParams(), s.getQueryParams()))
                .toList();

        LoadTestSpec loadSpec = new LoadTestSpec(scenario.getVirtualUsers(), scenario.getRampUpSeconds(),
                scenario.getDurationSeconds(), scenario.getIterations(), scenario.getThinkTimeMs(),
                scenario.getTargetRps());

        // P1-Q Etape B - parse ICI (transaction courte, Scenario encore
        // gere) le CSV data source eventuel du Scenario - jamais reparse a
        // chaque VU/iteration (cout constant, une seule fois par lancement).
        List<Map<String, String>> vuVariableRows = CsvDataSource.parse(scenario.getCsvData());

        VariableSources variableSources = new VariableSources(vuVariableRows, scenario.getCsvDistribution(),
                ScenarioVariables.parse(scenario.getVariables()), specs);

        return new PreparedExecution(saved.getId(), specs, applicationBaseUrl, auth, loadSpec, vuVariableRows, variableSources,
                scenario.getName(), triggeredByAppUserId, scheduleId, scenario.getRequestDetailMode());
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
        return finalizeExecution(executionId, outcome, wasCancelled, null);
    }

    /** "cancellationReason" : message d'annulation (ex. pause trop longue) ; null = annulation par l'utilisateur. */
    @Transactional
    public Execution finalizeExecution(UUID executionId, ScenarioExecutionOutcome outcome, boolean wasCancelled,
            String cancellationReason) {
        return finalizeExecution(executionId, outcome, wasCancelled, cancellationReason, false);
    }

    /** "detailSampleCapReached" : plafond memoire du detail des requetes atteint pendant l'execution. */
    @Transactional
    public Execution finalizeExecution(UUID executionId, ScenarioExecutionOutcome outcome, boolean wasCancelled,
            String cancellationReason, boolean detailSampleCapReached) {
        return finalizeExecution(executionId, outcome, wasCancelled, cancellationReason, detailSampleCapReached, null);
    }

    /** "cancelledBy" : utilisateur qui a demande l'annulation (affiche « Annulée par … »). */
    @Transactional
    public Execution finalizeExecution(UUID executionId, ScenarioExecutionOutcome outcome, boolean wasCancelled,
            String cancellationReason, boolean detailSampleCapReached, String cancelledBy) {
        Execution execution = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));

        if (!execution.getStatus().isActive()) {
            return execution;
        }

        // Etapes du scenario chargees en UNE requete : chaque resultat garde
        // une copie (nom, methode, URL) pour que l'historique survive a la
        // suppression ulterieure de l'etape (voir migration 017). Une etape
        // ne peut pas etre supprimee pendant une execution (StepServiceImpl).
        Map<UUID, Step> stepsById = new java.util.HashMap<>();
        for (Step s : stepRepository.findByScenarioId(execution.getScenario().getId(), Sort.unsorted())) {
            stepsById.put(s.getId(), s);
        }
        for (StepOutcome stepOutcome : outcome.stepOutcomes()) {
            Step step = stepsById.get(stepOutcome.stepId());
            ExecutionStepResult result = ExecutionStepResult.builder()
                    .execution(execution)
                    .step(step)
                    .stepName(step != null ? step.getName() : null)
                    .stepMethod(step != null ? step.getMethod() : null)
                    .stepUrl(step != null ? step.getUrl() : null)
                    .httpStatus(stepOutcome.httpStatus())
                    .responseTime(stepOutcome.responseTimeMs())
                    .success(stepOutcome.success())
                    .error(stepOutcome.error())
                    .timestamp(stepOutcome.timestamp())
                    .virtualUser(stepOutcome.virtualUser())
                    .iteration(stepOutcome.iteration())
                    .requestUrl(stepOutcome.requestUrl())
                    .build();
            ExecutionStepResult savedResult = executionStepResultRepository.save(result);
            if (stepOutcome.detail() != null) {
                requestDetailRepository.save(toDetailEntity(stepOutcome.detail(), savedResult, execution));
            }
        }
        execution.setDetailSampleCapReached(detailSampleCapReached);

        long successCount = outcome.stepOutcomes().stream().filter(StepOutcome::success).count();
        long failCount = outcome.stepOutcomes().size() - successCount;

        Instant finishedAt = Instant.now();
        closePause(execution, finishedAt);
        execution.setSuccessfulSteps((int) successCount);
        execution.setFailedSteps((int) failCount);
        // Statut final selon les seuils de reussite copies au lancement (voir ExecutionVerdict).
        ExecutionVerdict.Result verdict = ExecutionVerdict.decide(outcome, wasCancelled,
                cancellationReason != null ? cancellationReason : "Execution annulee par l'utilisateur.",
                execution.getMaxErrorRatePercent(), execution.getMaxP95Ms());
        execution.setStatus(verdict.status());
        execution.setErrorMessage(verdict.errorMessage());
        if (wasCancelled && cancellationReason == null && cancelledBy != null) execution.setCancelledBy(cancelledBy);
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
        return attemptCancellation(executionId, null);
    }

    /** "cancelledBy" : utilisateur qui annule (enregistre tout de suite pour une execution pas encore demarree). */
    @Transactional
    public CancellationAttempt attemptCancellation(UUID executionId, String cancelledBy) {
        // JOIN FETCH necessaire ici (contrairement a markRunning/
        // finalizeExecution) : le resultat est mappe en reponse HTTP par
        // l'appelant APRES le retour de cette methode transactionnelle.
        Execution execution = executionRepository.findByIdWithScenarioAndApplicationForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));

        if (execution.getStatus() == ExecutionStatus.QUEUED) {
            Instant finishedAt = Instant.now();
            execution.setStatus(ExecutionStatus.CANCELLED);
            execution.setErrorMessage("Execution annulee par l'utilisateur avant son demarrage.");
            execution.setCancelledBy(cancelledBy);
            execution.setFinishedAt(finishedAt);
            execution.setDuration(Duration.between(execution.getStartedAt(), finishedAt).toMillis());
            Execution saved = executionRepository.saveAndFlush(execution);
            return new CancellationAttempt(CancellationOutcome.CANCELLED_WHILE_QUEUED, saved);
        }

        if (execution.getStatus() == ExecutionStatus.RUNNING || execution.getStatus() == ExecutionStatus.PAUSED) {
            // Jamais d'ecriture ici : finalizeExecution reste l'UNIQUE
            // ecrivain du statut final d'une execution reellement demarree
            // (voir sa doc) - deux ecrivains concurrents sur la meme
            // transition produiraient exactement la course qu'on elimine ici.
            return new CancellationAttempt(CancellationOutcome.CANCELLATION_REQUESTED_WHILE_RUNNING, execution);
        }

        return new CancellationAttempt(CancellationOutcome.ALREADY_TERMINAL, execution);
    }

    // ------------------------------------------------------------ pause / reprise

    public static final String PAUSE_NOT_RUNNING = "Seule une exécution en cours peut être mise en pause.";
    public static final String RESUME_NOT_PAUSED = "Cette exécution n'est pas en pause.";
    public static final String PAUSE_FINISHED = "Cette exécution est déjà terminée, elle ne peut plus être mise en pause.";
    public static final String RESUME_FINISHED = "Cette exécution est déjà terminée, elle ne peut plus être reprise.";
    public static final String PAUSE_QUEUED = "Cette exécution n'a pas encore démarré : la pause sera possible dès qu'elle sera en cours.";
    public static final String ALREADY_PAUSED = "Cette exécution est déjà en pause.";
    public static final String NOT_ACTIVE_IN_MEMORY =
            "Cette exécution n'est plus suivie par le serveur (redémarrage du backend depuis son lancement) : "
                    + "elle ne peut être ni mise en pause ni reprise.";

    /** Message clair expliquant pourquoi une pause est refusee, selon le statut reel. */
    public static String pauseRefusal(ExecutionStatus status) {
        return switch (status) {
            case SUCCESS, THRESHOLDS_EXCEEDED, FAILED, CANCELLED -> PAUSE_FINISHED;
            case QUEUED -> PAUSE_QUEUED;
            case PAUSED -> ALREADY_PAUSED;
            case RUNNING -> PAUSE_NOT_RUNNING;
        };
    }

    /** Message clair expliquant pourquoi une reprise est refusee, selon le statut reel. */
    public static String resumeRefusal(ExecutionStatus status) {
        return status.isActive() ? RESUME_NOT_PAUSED : RESUME_FINISHED;
    }

    /** Statut actuel (404 clair si l'execution n'existe pas). */
    @Transactional(readOnly = true)
    public ExecutionStatus currentStatus(UUID executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Exécution introuvable (elle a peut-être été supprimée)."))
                .getStatus();
    }

    /**
     * RUNNING -> PAUSED sous verrou (meme verrou que markRunning/finalizeExecution/
     * attemptCancellation : jamais deux transitions concurrentes sur la meme ligne).
     * 409 si l'execution n'est pas en cours (en attente, deja en pause, terminee).
     */
    @Transactional
    public Execution markPaused(UUID executionId) {
        Execution execution = executionRepository.findByIdWithScenarioAndApplicationForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));
        if (execution.getStatus() != ExecutionStatus.RUNNING) {
            throw new ConflictException(pauseRefusal(execution.getStatus()));
        }
        execution.setStatus(ExecutionStatus.PAUSED);
        execution.setPausedAt(Instant.now());
        return executionRepository.saveAndFlush(execution);
    }

    /** PAUSED -> RUNNING sous verrou ; la duree de la pause s'ajoute a pausedDurationMs. */
    @Transactional
    public Execution markResumed(UUID executionId) {
        Execution execution = executionRepository.findByIdWithScenarioAndApplicationForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));
        if (execution.getStatus() != ExecutionStatus.PAUSED) {
            throw new ConflictException(resumeRefusal(execution.getStatus()));
        }
        closePause(execution, Instant.now());
        execution.setStatus(ExecutionStatus.RUNNING);
        return executionRepository.saveAndFlush(execution);
    }

    /** Chronologie (VU actifs, pauses) enregistree apres la finalisation ; trop volumineuse = ignoree. */
    @Transactional
    public void saveTimeline(UUID executionId, com.loadpilot.backend.service.execution.RunningExecutionHandle.Timeline timeline) {
        String json = writeJson(Map.of("vus", timeline.virtualUsers(), "pauses", timeline.pauses()));
        if (json == null || json.length() > 100000) return;
        executionRepository.findById(executionId).ifPresent(execution -> {
            execution.setTimelineJson(json);
            executionRepository.saveAndFlush(execution);
        });
    }

    // ------------------------------------------------------------ monitoring CPU / RAM

    /** Monitoring de l'application testee par cette execution (enabled() = false si non configure). */
    @Transactional(readOnly = true)
    public com.loadpilot.backend.service.monitoring.MonitoringConfig monitoringConfig(UUID executionId) {
        return executionRepository.findByIdWithScenarioAndApplication(executionId)
                .map(e -> monitoringSupport.toConfig(e.getScenario().getApplication()))
                .orElse(null);
    }

    /**
     * Mesures CPU / RAM de l'execution : JSON pour la courbe, resume (CPU moyen / max, RAM max) pour
     * les listes, message si le monitoring n'a pas toujours repondu.
     */
    @Transactional
    public void saveResources(UUID executionId, com.loadpilot.backend.service.monitoring.ResourceTrack track) {
        List<com.loadpilot.backend.service.monitoring.ResourceTrack.Sample> samples = track.samples();
        List<Object[]> rows = new java.util.ArrayList<>(samples.size());
        double cpuSum = 0;
        int cpuCount = 0;
        Double cpuMax = null;
        Double memMax = null;
        for (var s : samples) {
            rows.add(new Object[] {s.atMs(), s.cpuPercent(), s.memoryMb()});
            if (s.cpuPercent() != null) {
                cpuSum += s.cpuPercent();
                cpuCount++;
                cpuMax = cpuMax == null ? s.cpuPercent() : Math.max(cpuMax, s.cpuPercent());
            }
            if (s.memoryMb() != null) memMax = memMax == null ? s.memoryMb() : Math.max(memMax, s.memoryMb());
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("source", track.source());
        payload.put("intervalSeconds", track.intervalSeconds());
        payload.put("memoryMetric", track.memoryMetric());
        payload.put("samples", rows);
        String json = writeJson(payload);
        Double cpuAvg = cpuCount == 0 ? null : Math.round(cpuSum / cpuCount * 10) / 10.0;
        Double finalCpuMax = cpuMax;
        Double finalMemMax = memMax;
        String message = track.message();
        executionRepository.findById(executionId).ifPresent(execution -> {
            execution.setResourceJson(json != null && json.length() <= 200000 ? json : null);
            execution.setCpuAvgPercent(cpuAvg);
            execution.setCpuMaxPercent(finalCpuMax);
            execution.setMemoryMaxMb(finalMemMax);
            execution.setMonitoringMessage(message == null ? null : message.length() > 1000 ? message.substring(0, 1000) : message);
            executionRepository.saveAndFlush(execution);
        });
    }

    // ------------------------------------------------------------ detail des requetes

    private static final int HEADERS_MAX = 16000;
    private static final int CHECKS_MAX = 8000;

    private ExecutionRequestDetail toDetailEntity(RequestDetail d, ExecutionStepResult result, Execution execution) {
        return ExecutionRequestDetail.builder()
                .result(result)
                .execution(execution)
                .kind(d.kind().name())
                .requestMethod(d.method())
                .requestUrl(d.url())
                .requestHeaders(headersJson(d.requestHeaders()))
                .requestBody(d.requestBody())
                .requestBodyTruncated(d.requestBodyTruncated())
                .responseStatus(d.responseStatus())
                .responseHeaders(headersJson(d.responseHeaders()))
                .responseBody(d.responseBody())
                .responseBodyTruncated(d.responseBodyTruncated())
                .responseSizeBytes(d.responseSizeBytes())
                .checks(d.checks() == null ? null : limit(writeJson(d.checks()), CHECKS_MAX))
                .build();
    }

    /** En-tetes en JSON ; valeurs raccourcies si l'ensemble depasse la colonne (jamais d'echec d'ecriture). */
    private String headersJson(Map<String, String> headers) {
        if (headers == null) return null;
        String json = writeJson(headers);
        if (json.length() <= HEADERS_MAX) return json;
        Map<String, String> shortened = new java.util.LinkedHashMap<>();
        headers.forEach((k, v) -> shortened.put(k, v != null && v.length() > 200 ? v.substring(0, 200) + "…" : v));
        return limit(writeJson(shortened), HEADERS_MAX);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static String limit(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Termine la pause en cours (s'il y en a une) en cumulant sa duree. */
    private static void closePause(Execution execution, Instant now) {
        if (execution.getPausedAt() != null) {
            long total = execution.getPausedDurationMs() == null ? 0L : execution.getPausedDurationMs();
            execution.setPausedDurationMs(total + Math.max(0L, Duration.between(execution.getPausedAt(), now).toMillis()));
            execution.setPausedAt(null);
        }
    }
}
