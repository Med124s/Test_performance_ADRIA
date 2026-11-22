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

/** Transactions courtes d'une execution : preparation, changements de statut sous verrou, ecriture des resultats et finalisation. */
@Service
@RequiredArgsConstructor
public class ExecutionTransactionHelper {

    private static final Sort STEP_ORDER = Sort.by(Sort.Direction.ASC, "order").and(Sort.by(Sort.Direction.ASC, "id"));

    public static final String INACTIVE_NOT_RUNNABLE = "Le scénario est inactif, activez-le pour l'exécuter.";

    public static final String DRAFT_NOT_RUNNABLE =
            "Ce scénario est un brouillon : terminez-le dans l'assistant (« Reprendre ») avant de l'exécuter.";

    private final ScenarioRepository scenarioRepository;
    private final com.loadpilot.backend.service.ExecutionLimitsService executionLimits;
    private final StepRepository stepRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository executionStepResultRepository;
    private final com.loadpilot.backend.repository.AppUserRepository appUserRepository;
    private final ApplicationAuthSupport authSupport;
    private final com.loadpilot.backend.repository.ExecutionRequestDetailRepository requestDetailRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.loadpilot.backend.service.monitoring.MonitoringSupport monitoringSupport;

    /** Cree l'execution (QUEUED) et copie tout ce qu'il faut pour la lancer hors transaction. */
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
        boolean stress = scenario.getLoadMode() == com.loadpilot.backend.enums.LoadMode.STRESS;
        // Une planification ne declenche jamais un test de stress.
        if (stress && scheduleId != null) {
            throw new BadRequestException(ScheduledExecutionServiceImpl.STRESS_NOT_SCHEDULABLE);
        }
        // Scenario au-dela des regles ou limites actuelles : non modifie, mais pas lance (mode stress : reglages ignores).
        com.loadpilot.backend.service.validation.LoadLimits limits = executionLimits.loadLimits();
        java.util.List<String> loadProblems = stress ? java.util.List.of()
                : com.loadpilot.backend.service.validation.LoadRules.validate(scenario.getVirtualUsers(),
                        scenario.getThinkTimeMs(), scenario.getRampUpSeconds(), scenario.getDurationSeconds(), scenario.getIterations(),
                        scenario.getTargetRps(), limits);
        if (!loadProblems.isEmpty()) {
            throw new BadRequestException(String.join(" ", loadProblems) + " Modifiez la charge du scénario avant de le lancer.");
        }

        String applicationBaseUrl = scenario.getApplication().getUrl();
        // Authentification de l'application copiee au lancement.
        ApplicationAuthSpec auth = authSupport.toSpec(scenario.getApplication());

        AppUser triggeredByRef = triggeredByAppUserId != null
                ? appUserRepository.getReferenceById(triggeredByAppUserId) : null;
        // Limites de la montee automatique, copiees au lancement ; null = mode manuel.
        com.loadpilot.backend.service.execution.StressRamp.Limits stressLimits = stress
                ? new com.loadpilot.backend.service.execution.StressRamp.Limits(limits.maxVirtualUsers(), limits.maxTargetRps(),
                        limits.maxDurationSeconds())
                : null;

        Execution execution = Execution.builder()
                .scenario(scenario)
                .startedAt(Instant.now())
                .status(ExecutionStatus.QUEUED)
                // Mode stress : la montee peut aller jusqu'a la limite d'utilisateurs par test, sans ramp-up ni duree saisis.
                .virtualUsers(stressLimits != null ? stressLimits.maxVirtualUsers() : scenario.getVirtualUsers())
                .rampUpSeconds(stressLimits != null ? 0 : scenario.getRampUpSeconds())
                .durationSeconds(stressLimits != null ? null : scenario.getDurationSeconds())
                .iterations(stressLimits != null ? null : scenario.getIterations())
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
                        s.getCaptureVariableName(), s.getCaptureJsonPath(), s.getPathParams(), s.getQueryParams(),
                        s.getCaptureSource(), s.getMaxResponseTimeMs(), s.getAssertionJsonPath(),
                        s.getAssertionJsonOperator(), s.getAssertionJsonValue()))
                .toList();

        int thinkTimeVariation = scenario.getThinkTimeVariationPercent() == null ? 0 : scenario.getThinkTimeVariationPercent();
        LoadTestSpec loadSpec = stressLimits != null ? LoadTestSpec.stress(stressLimits, thinkTimeVariation)
                : new LoadTestSpec(scenario.getVirtualUsers(), scenario.getRampUpSeconds(),
                        scenario.getDurationSeconds(), scenario.getIterations(), scenario.getThinkTimeMs(),
                        scenario.getTargetRps(), com.loadpilot.backend.service.execution.ThrottlePolicy.of(scenario.getThrottleOn429(),
                                scenario.getThrottleDefaultDelaySeconds(), scenario.getThrottleMaxRetries()),
                        thinkTimeVariation);

        // CSV lu une seule fois par lancement.
        List<Map<String, String>> vuVariableRows = CsvDataSource.parse(scenario.getCsvData());

        VariableSources variableSources = new VariableSources(vuVariableRows, scenario.getCsvDistribution(),
                ScenarioVariables.parse(scenario.getVariables()), specs);

        return new PreparedExecution(saved.getId(), specs, applicationBaseUrl, auth, loadSpec, vuVariableRows, variableSources,
                scenario.getName(), triggeredByAppUserId, scheduleId, scenario.getRequestDetailMode(),
                // Mode stress : pas de frein sur seuil saisi, la rupture est detectee par rapport a la baseline.
                stressLimits != null ? null : com.loadpilot.backend.service.execution.ErrorRateBrake.Config.of(
                        scenario.getErrorBrakeRatePercent(), scenario.getErrorBrakeWindowSeconds()),
                com.loadpilot.backend.service.http.NetworkProfile.of(scenario.getApplication()));
    }

    /** QUEUED -> RUNNING sous verrou ; false si l'execution a ete annulee entre-temps. */
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

    /** Enregistre les derniers resultats et le statut final ; "wasCancelled" force CANCELLED. */
    @Transactional
    public Execution finalizeExecution(UUID executionId, ScenarioExecutionOutcome outcome, boolean wasCancelled) {
        return finalizeExecution(executionId, outcome, wasCancelled, null);
    }

    /** "cancellationReason" : motif d'une annulation automatique ; null = annulation par l'utilisateur. */
    @Transactional
    public Execution finalizeExecution(UUID executionId, ScenarioExecutionOutcome outcome, boolean wasCancelled,
            String cancellationReason) {
        return finalizeExecution(executionId, outcome, wasCancelled, cancellationReason, false);
    }

    /** "detailSampleCapReached" : plafond memoire du detail des requetes atteint. */
    @Transactional
    public Execution finalizeExecution(UUID executionId, ScenarioExecutionOutcome outcome, boolean wasCancelled,
            String cancellationReason, boolean detailSampleCapReached) {
        return finalizeExecution(executionId, outcome, wasCancelled, cancellationReason, detailSampleCapReached, null);
    }

    /** "cancelledBy" : utilisateur qui a demande l'annulation. */
    @Transactional
    public Execution finalizeExecution(UUID executionId, ScenarioExecutionOutcome outcome, boolean wasCancelled,
            String cancellationReason, boolean detailSampleCapReached, String cancelledBy) {
        Execution execution = executionRepository.findByIdForUpdate(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));

        if (!execution.getStatus().isActive()) {
            return execution;
        }

        // Resultats pas encore enregistres (aucun s'ils ont ete ecrits pendant le test).
        if (!outcome.stepOutcomes().isEmpty()) {
            Map<UUID, Step> stepsById = new java.util.HashMap<>();
            for (Step s : stepRepository.findByScenarioId(execution.getScenario().getId(), Sort.unsorted())) {
                stepsById.put(s.getId(), s);
            }
            for (StepOutcome stepOutcome : outcome.stepOutcomes()) {
                saveResult(stepOutcome, execution, stepsById.get(stepOutcome.stepId()));
            }
        }
        execution.setDetailSampleCapReached(detailSampleCapReached);

        // Compteurs relus en base.
        executionStepResultRepository.flush();
        long successCount = executionStepResultRepository.countByExecutionIdAndSuccess(executionId, true);
        long failCount = executionStepResultRepository.countByExecutionIdAndSuccess(executionId, false);

        Instant finishedAt = Instant.now();
        closePause(execution, finishedAt);
        execution.setSuccessfulSteps((int) successCount);
        execution.setFailedSteps((int) failCount);
        // Statut final selon les seuils de reussite copies au lancement.
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

    /** Ecrit un lot de resultats pendant le test, dans sa propre transaction ; steps sert a copier nom, methode et URL de chaque etape. */
    @Transactional
    public void appendResults(UUID executionId, List<StepOutcome> batch, Map<UUID, StepExecutionSpec> steps) {
        Execution execution = executionRepository.getReferenceById(executionId);
        for (StepOutcome outcome : batch) {
            StepExecutionSpec spec = outcome.stepId() == null ? null : steps.get(outcome.stepId());
            Step step = spec == null ? null : stepRepository.getReferenceById(spec.stepId());
            saveResult(outcome, execution, step, spec);
        }
    }

    private void saveResult(StepOutcome stepOutcome, Execution execution, Step step) {
        saveResult(stepOutcome, execution, step, null);
    }

    /** Enregistre un resultat et son detail eventuel. */
    private void saveResult(StepOutcome stepOutcome, Execution execution, Step step, StepExecutionSpec spec) {
        ExecutionStepResult result = ExecutionStepResult.builder()
                .execution(execution)
                .step(step)
                .stepName(spec != null ? spec.name() : step != null ? step.getName() : null)
                .stepMethod(spec != null ? spec.method() : step != null ? step.getMethod() : null)
                .stepUrl(spec != null ? spec.url() : step != null ? step.getUrl() : null)
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

    /** Passe l'execution a FAILED sous verrou, seulement si elle est encore active. */
    @Transactional
    public boolean markFailedIfActive(UUID executionId, String errorMessage) {
        Execution execution = executionRepository.findByIdForUpdate(executionId).orElse(null);
        if (execution == null || !execution.getStatus().isActive()) {
            return false;
        }
        Instant finishedAt = Instant.now();
        closePause(execution, finishedAt);
        // Resultats deja ecrits pendant le test : conserves et comptes.
        execution.setSuccessfulSteps((int) executionStepResultRepository.countByExecutionIdAndSuccess(executionId, true));
        execution.setFailedSteps((int) executionStepResultRepository.countByExecutionIdAndSuccess(executionId, false));
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setErrorMessage(errorMessage);
        execution.setFinishedAt(finishedAt);
        execution.setDuration(Duration.between(execution.getStartedAt(), finishedAt).toMillis());
        executionRepository.saveAndFlush(execution);
        return true;
    }

    /** Tentative d'annulation sous verrou : CANCELLED directement si l'execution est en attente, sinon resultat a traiter par l'appelant. */
    @Transactional
    public CancellationAttempt attemptCancellation(UUID executionId) {
        return attemptCancellation(executionId, null);
    }

    /** "cancelledBy" : utilisateur qui annule. */
    @Transactional
    public CancellationAttempt attemptCancellation(UUID executionId, String cancelledBy) {
        // JOIN FETCH : le resultat est converti en reponse HTTP apres la transaction.
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
            // Execution en cours : le statut final sera ecrit par finalizeExecution.
            return new CancellationAttempt(CancellationOutcome.CANCELLATION_REQUESTED_WHILE_RUNNING, execution);
        }

        return new CancellationAttempt(CancellationOutcome.ALREADY_TERMINAL, execution);
    }

    // ---- Pause / reprise ----

    public static final String PAUSE_NOT_RUNNING = "Seule une exécution en cours peut être mise en pause.";
    public static final String RESUME_NOT_PAUSED = "Cette exécution n'est pas en pause.";
    public static final String PAUSE_FINISHED = "Cette exécution est déjà terminée, elle ne peut plus être mise en pause.";
    public static final String RESUME_FINISHED = "Cette exécution est déjà terminée, elle ne peut plus être reprise.";
    public static final String PAUSE_QUEUED = "Cette exécution n'a pas encore démarré : la pause sera possible dès qu'elle sera en cours.";
    public static final String ALREADY_PAUSED = "Cette exécution est déjà en pause.";
    public static final String NOT_ACTIVE_IN_MEMORY =
            "Cette exécution n'est plus suivie par le serveur (redémarrage du backend depuis son lancement) : "
                    + "elle ne peut être ni mise en pause ni reprise.";

    /** Message expliquant pourquoi une pause est refusee. */
    public static String pauseRefusal(ExecutionStatus status) {
        return switch (status) {
            case SUCCESS, THRESHOLDS_EXCEEDED, FAILED, CANCELLED -> PAUSE_FINISHED;
            case QUEUED -> PAUSE_QUEUED;
            case PAUSED -> ALREADY_PAUSED;
            case RUNNING -> PAUSE_NOT_RUNNING;
        };
    }

    /** Message expliquant pourquoi une reprise est refusee. */
    public static String resumeRefusal(ExecutionStatus status) {
        return status.isActive() ? RESUME_NOT_PAUSED : RESUME_FINISHED;
    }

    /** Statut actuel ; 404 si l'execution n'existe pas. */
    @Transactional(readOnly = true)
    public ExecutionStatus currentStatus(UUID executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Exécution introuvable (elle a peut-être été supprimée)."))
                .getStatus();
    }

    /** RUNNING -> PAUSED sous verrou ; 409 si l'execution n'est pas en cours. */
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

    /** Enregistre la chronologie (utilisateurs actifs, pauses) ; ignoree si trop volumineuse. */
    @Transactional
    public void saveTimeline(UUID executionId, com.loadpilot.backend.service.execution.RunningExecutionHandle.Timeline timeline) {
        String json = writeJson(Map.of("vus", timeline.virtualUsers(), "pauses", timeline.pauses()));
        if (json == null || json.length() > 100000) return;
        executionRepository.findById(executionId).ifPresent(execution -> {
            execution.setTimelineJson(json);
            executionRepository.saveAndFlush(execution);
        });
    }

    // ---- Monitoring CPU / RAM ----

    /** Monitoring de l'application testee par cette execution (enabled() = false si non configure). */
    @Transactional(readOnly = true)
    public com.loadpilot.backend.service.monitoring.MonitoringConfig monitoringConfig(UUID executionId) {
        return executionRepository.findByIdWithScenarioAndApplication(executionId)
                .map(e -> monitoringSupport.toConfig(e.getScenario().getApplication()))
                .orElse(null);
    }

    /** Enregistre les mesures CPU / RAM : JSON pour la courbe, resume pour les listes, message si le monitoring n'a pas toujours repondu. */
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

    // ---- Detail des requetes ----

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

    /** En-tetes en JSON ; valeurs raccourcies si l'ensemble depasse la taille de la colonne. */
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
