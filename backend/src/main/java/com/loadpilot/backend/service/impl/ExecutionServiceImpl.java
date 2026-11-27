package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.execution.LiveStats;
import com.loadpilot.backend.dto.request.ExecutionHistoryFilterRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.response.ExecutionDetailResponse;
import com.loadpilot.backend.dto.response.ExecutionHistoryResponse;
import com.loadpilot.backend.dto.response.ExecutionPageResponse;
import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.ExecutionResponse;
import com.loadpilot.backend.dto.response.ExecutionStatusResponse;
import com.loadpilot.backend.dto.response.ExecutionStepResultResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.ExecutionMapper;
import com.loadpilot.backend.mapper.ExecutionStepResultMapper;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.ExecutionService;
import com.loadpilot.backend.service.NotificationService;
import com.loadpilot.backend.service.execution.BreakpointWatcher;
import com.loadpilot.backend.service.execution.CancellationAttempt;
import com.loadpilot.backend.service.execution.CancellationOutcome;
import com.loadpilot.backend.service.execution.ErrorRateBrake;
import com.loadpilot.backend.service.execution.ExecutionEngine;
import com.loadpilot.backend.service.execution.PreparedExecution;
import com.loadpilot.backend.service.execution.RequestDetailRecorder;
import com.loadpilot.backend.service.execution.RunningExecutionHandle;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.loadpilot.backend.service.execution.ScenarioExecutionOutcome;
import com.loadpilot.backend.service.execution.StressRamp;
import com.loadpilot.backend.service.execution.StressSettings;
import com.loadpilot.backend.service.monitoring.MonitoringConfig;
import com.loadpilot.backend.service.monitoring.ResourceMonitor;
import com.loadpilot.backend.service.monitoring.ResourceTrack;
import com.loadpilot.backend.service.report.CsvUtils;
import com.loadpilot.backend.service.report.ExecutionStatistics;
import com.loadpilot.backend.service.report.PerformanceStatisticsService;
import com.loadpilot.backend.service.report.ReportErrorEntry;
import com.loadpilot.backend.service.report.StepReportEntry;
import com.loadpilot.backend.service.report.ThroughputCalculator;
import com.loadpilot.backend.specification.ExecutionSpecifications;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.audit.AuditTargets;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration des executions : lancement en arriere-plan, suivi, pause, reprise, annulation, historique et rapport. */
@Service
@RequiredArgsConstructor
public class ExecutionServiceImpl implements ExecutionService {

    static final String STRESS_NOT_PAUSABLE = "Un test de stress ne se met pas en pause (la comparaison à la baseline serait faussée) : "
            + "laissez-le se terminer ou annulez-le.";

    private static final Logger log = LoggerFactory.getLogger(ExecutionServiceImpl.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "startedAt");

    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository executionStepResultRepository;
    private final ScenarioRepository scenarioRepository;
    private final ExecutionMapper executionMapper;
    private final ExecutionStepResultMapper executionStepResultMapper;
    private final ExecutionEngine executionEngine;
    private final ExecutionTransactionHelper transactionHelper;
    private final AuditLogService auditLogService;
    private final AuditTargets auditTargets;
    private final RunningExecutionRegistry registry;
    private final ExecutorService loadTestExecutor;
    private final PerformanceStatisticsService performanceStatisticsService;
    private final NotificationService notificationService;
    private final com.loadpilot.backend.repository.ExecutionRequestDetailRepository requestDetailRepository;
    private final ResourceMonitor resourceMonitor;
    private final ExecutionStatsService executionStatsService;
    private final ReportSnapshots reportSnapshots;
    // Fourni a la demande : ReportSnapshotService depend lui-meme de ce service.
    private final org.springframework.beans.factory.ObjectProvider<ReportSnapshotService> reportSnapshotService;
    private final ScheduleNotifier scheduleNotifier;
    // Garde-fou des lectures qui chargent tous les resultats.
    private final ResultLoadGuard resultLoadGuard;
    private final com.loadpilot.backend.service.monitoring.InjectorMonitor injectorMonitor;
    private final com.loadpilot.backend.service.ExecutionLimitsService executionLimits;

    // Plafond memoire du detail des requetes par execution (Mo).
    @org.springframework.beans.factory.annotation.Value("${app.execution.request-detail.max-memory-mb:50}")
    private long requestDetailMaxMemoryMb = 50;

    @Override
    public ExecutionResponse execute(ExecutionRequest request, UUID triggeredByAppUserId) {
        return doExecute(request.scenarioId(), triggeredByAppUserId, null);
    }

    @Override
    public ExecutionResponse executeScheduled(UUID scenarioId, UUID triggeredByAppUserId, UUID scheduleId) {
        return doExecute(scenarioId, triggeredByAppUserId, scheduleId);
    }

    private ExecutionResponse doExecute(UUID scenarioId, UUID triggeredByAppUserId, UUID scheduleId) {
        boolean capacityReserved = false;
        int virtualUsers = 0;
        boolean stress = false;
        try {
            // Nombre d'utilisateurs virtuels, necessaire pour reserver la capacite ; en mode stress, la montee
            // automatique peut aller jusqu'a la limite d'utilisateurs par test.
            Scenario scenario = scenarioRepository.findById(scenarioId)
                    .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + scenarioId));
            stress = scenario.getLoadMode() == com.loadpilot.backend.enums.LoadMode.STRESS;
            virtualUsers = stress ? executionLimits.maxVirtualUsersPerTest() : scenario.getVirtualUsers();

            // Refus avant toute ecriture si une limite de capacite serait depassee ; un test de stress tourne seul.
            registry.reserveCapacity(virtualUsers, stress);
            capacityReserved = true;

            // Valide le scenario et cree l'execution QUEUED avec ses parametres de charge.
            PreparedExecution prepared = transactionHelper.prepareAndStart(scenarioId, triggeredByAppUserId, scheduleId);

            RunningExecutionHandle handle = registry.registerReserved(prepared.executionId(), virtualUsers, stress);
            handle.detailRecorder(new RequestDetailRecorder(prepared.requestDetailMode(), requestDetailMaxMemoryMb * 1024L * 1024L));
            // Desormais, la capacite est liberee par registry.unregister() (runAsync, bloc finally).
            capacityReserved = false;
            // Monitoring CPU / RAM de l'application testee (optionnel).
            MonitoringConfig monitoring = monitoringConfigOrNull(prepared.executionId());
            if (monitoring != null) handle.resources(new ResourceTrack(monitoring.type().name(), monitoring.intervalSeconds()));
            loadTestExecutor.submit(() -> runAsync(prepared, handle, monitoring));

            Execution queued = executionRepository.findByIdWithScenarioAndApplication(prepared.executionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + prepared.executionId()));
            if (scheduleId == null) {
                auditLogService.record(AuditEvent.of(AuditAction.EXECUTE, AuditModule.EXECUTION)
                        .target(AuditTargetType.EXECUTION, queued.getId(), AuditTargets.executionLabel(queued.getScenario().getName(), queued.getStartedAt() != null ? queued.getStartedAt() : Instant.now()))
                        .description("Exécution lancée (" + virtualUsers + " utilisateur" + (virtualUsers > 1 ? "s" : "") + " virtuel" + (virtualUsers > 1 ? "s" : "") + ")"));
            }
            return executionMapper.toResponse(queued);
        } catch (RuntimeException e) {
            // Capacite reservee mais aucune execution creee : liberation.
            if (capacityReserved) {
                registry.releaseReservationWithoutRegistering(virtualUsers, stress);
            }
            auditLogService.record(launchAudit(AuditEvent.of(AuditAction.EXECUTE, AuditModule.EXECUTION), scenarioId, scheduleId)
                    .description("Exécution non lancée").failed(e));
            throw e;
        }
    }

    /** Configuration du monitoring de l'execution ; null si absent ou illisible. */
    private MonitoringConfig monitoringConfigOrNull(UUID executionId) {
        try {
            MonitoringConfig config = transactionHelper.monitoringConfig(executionId);
            return config != null && config.enabled() ? config : null;
        } catch (RuntimeException e) {
            log.warn("Configuration du monitoring illisible pour l'execution {} : {}", executionId, e.getClass().getSimpleName());
            return null;
        }
    }

    private void runAsync(PreparedExecution prepared, RunningExecutionHandle handle, MonitoringConfig monitoring) {
        ResourceMonitor.Session monitoringSession = null;
        ErrorRateBrake.Session brake = null;
        BreakpointWatcher.Session breakpointWatcher = null;
        com.loadpilot.backend.service.monitoring.InjectorMonitor.Session injectorSession = null;
        com.loadpilot.backend.service.execution.ResultStream resultStream = null;
        UUID finishedId = null;
        try {
            ScenarioExecutionOutcome outcome;
            // Execution deja annulee avant son demarrage : aucune requete n'est envoyee.
            if (handle.isCancelled() || !transactionHelper.markRunning(prepared.executionId())) {
                outcome = new ScenarioExecutionOutcome(List.of(), ExecutionStatus.FAILED, null);
            } else {
                if (monitoring != null) monitoringSession = resourceMonitor.start(monitoring, handle.resources());
                // Mesures de LoadPilot lui-meme, a la meme frequence que celles de la cible (5 s sans monitoring) ;
                // mode stress : plus souvent, pour arreter vite un test que LoadPilot ne peut plus mesurer.
                boolean stress = prepared.loadSpec().stress() != null;
                com.loadpilot.backend.service.monitoring.InjectorTrack injectorTrack = injectorMonitor.newTrack();
                handle.injector(injectorTrack);
                injectorSession = injectorMonitor.start(injectorTrack, stress ? StressSettings.INJECTOR_INTERVAL_SECONDS
                        : monitoring != null ? monitoring.intervalSeconds()
                        : com.loadpilot.backend.service.monitoring.InjectorMonitor.DEFAULT_INTERVAL_SECONDS);
                brake = ErrorRateBrake.start(prepared.errorBrake(), handle,
                        reason -> cancelForErrorRate(prepared.executionId(), reason));
                if (stress) breakpointWatcher = BreakpointWatcher.start(handle, decision -> onStressDecision(prepared, handle, decision));
                // Resultats ecrits en base pendant le test, par lots.
                resultStream = openResultStream(prepared);
                handle.resultStream(resultStream);
                outcome = executionEngine.execute(prepared.steps(), prepared.applicationBaseUrl(), prepared.auth(),
                        prepared.loadSpec(), handle, prepared.variableSources(), prepared.network());
                brake.stop();
                if (breakpointWatcher != null) breakpointWatcher.stop();
                injectorSession.stop();
                // Dernier lot ecrit avant la finalisation.
                closeResultStream(resultStream, prepared.executionId());
            }

            RunningExecutionHandle.Timeline timeline = handle.timeline(System.currentTimeMillis());
            if (monitoringSession != null) {
                monitoringSession.stop();
                monitoringSession = null;
                try {
                    transactionHelper.saveResources(prepared.executionId(), handle.resources());
                } catch (Exception e) {
                    log.warn("Mesures CPU / RAM non enregistrees pour l'execution {} : {}", prepared.executionId(), e.getClass().getSimpleName());
                }
            }
            // Mode stress : point de rupture, ou limite de LoadPilot atteinte, lus dans le handle.
            com.loadpilot.backend.service.execution.StressResult stressResult = prepared.loadSpec().stress() != null
                    ? com.loadpilot.backend.service.execution.StressResult.of(handle) : null;
            Execution finalized = transactionHelper.finalizeExecution(prepared.executionId(), outcome, handle.isCancelled(),
                    handle.cancellationReason(), handle.detailRecorder() != null && handle.detailRecorder().sampleCapReached(),
                    handle.cancelledBy(), stressResult);

            AuditResult auditResult = finalized.getStatus() == ExecutionStatus.SUCCESS
                    ? AuditResult.SUCCESS : AuditResult.FAILURE;
            // Action automatique : acteur "Systeme".
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.FINISH, AuditModule.EXECUTION), finalized.getId())
                    .system(prepared.scheduleId() != null ? "Exécution planifiée" : "Fin d’exécution")
                    .result(auditResult)
                    .description(finishDescription(finalized)));
            // Execution planifiee : notification envoyee plus bas, une fois le P95 calcule.
            if (prepared.scheduleId() == null) notifyTerminalStatus(prepared, finalized.getStatus());

            // Chronologie pour l'analyse dans le temps.
            try {
                transactionHelper.saveTimeline(finalized.getId(), timeline);
            } catch (Throwable e) {
                logBackgroundFailure("Enregistrement de la chronologie", finalized.getId(), e);
            }

            // Resume pour le tableau de bord (P95, debit, P95 par etape).
            try {
                executionStatsService.compute(finalized.getId());
            } catch (Throwable e) {
                logBackgroundFailure("Calcul du resume", finalized.getId(), e);
            }
            finishedId = finalized.getId();
            if (prepared.scheduleId() != null) notifyScheduled(prepared, finalized.getStatus());
        } catch (Throwable e) {
            // Throwable : une Error (ex. OutOfMemoryError) doit aussi etre journalisee.
            log.error("Echec inattendu de l'execution asynchrone {} : {}", prepared.executionId(), e.getClass().getName());
            // Resultats deja obtenus ecrits avant de marquer l'echec.
            long kept = closeResultStream(resultStream, prepared.executionId());
            boolean markedFailed = markFailedAfterUnexpectedError(prepared.executionId(), e, kept);
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.FINISH, AuditModule.EXECUTION), prepared.executionId())
                    .system(prepared.scheduleId() != null ? "Exécution planifiée" : "Fin d’exécution")
                    .description("Exécution interrompue par une erreur inattendue").failed(e));
            // Statut de fin deja enregistre : notification deja envoyee.
            if (markedFailed) notifyTerminalStatus(prepared, ExecutionStatus.FAILED);
        } finally {
            if (brake != null) brake.stop();
            if (breakpointWatcher != null) breakpointWatcher.stop();
            if (injectorSession != null) injectorSession.stop();
            if (monitoringSession != null) monitoringSession.stop();
            closeResultStream(resultStream, prepared.executionId());
            registry.unregister(prepared.executionId());
        }
        // Rapport fige, calcule apres la liberation de la capacite.
        if (finishedId != null) {
            try {
                reportSnapshotService.getObject().capture(finishedId);
            } catch (Throwable e) {
                logBackgroundFailure("Rapport fige", finishedId, e);
            }
        }
    }

    /** Flux d'ecriture des resultats d'une execution : un lot = une transaction. */
    private com.loadpilot.backend.service.execution.ResultStream openResultStream(PreparedExecution prepared) {
        java.util.Map<UUID, com.loadpilot.backend.service.execution.StepExecutionSpec> stepsById = new java.util.HashMap<>();
        for (var spec : prepared.steps()) {
            if (spec.stepId() != null) stepsById.put(spec.stepId(), spec);
        }
        UUID executionId = prepared.executionId();
        return new com.loadpilot.backend.service.execution.ResultStream(executionId.toString(),
                batch -> transactionHelper.appendResults(executionId, batch, stepsById));
    }

    /** Ecrit les resultats restants et ferme le flux ; renvoie le nombre de resultats enregistres. */
    static long closeResultStream(com.loadpilot.backend.service.execution.ResultStream stream, UUID executionId) {
        if (stream == null) return 0;
        try {
            stream.close();
            if (stream.lost() > 0) {
                log.error("Execution {} : {} resultat(s) non enregistre(s) (base indisponible).", executionId, stream.lost());
            }
            return stream.written();
        } catch (Throwable e) {
            log.error("Execution {} : ecriture des derniers resultats impossible : {}", executionId, e.getClass().getName());
            return stream.written();
        }
    }

    /** Journalise l'echec d'un traitement en arriere-plan : en erreur pour une Error, sinon en avertissement. */
    static void logBackgroundFailure(String what, UUID executionId, Throwable e) {
        if (e instanceof Error) {
            log.error("{} impossible pour l'execution {} : {} ({})", what, executionId, e.getClass().getSimpleName(), e.getMessage());
        } else {
            log.warn("{} impossible pour l'execution {} : {}", what, executionId,
                    e instanceof com.loadpilot.backend.exception.ResultSetTooLargeException ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** Passe en FAILED une execution encore active apres une erreur inattendue ; ne leve jamais d'exception. */
    private boolean markFailedAfterUnexpectedError(UUID executionId, Throwable error, long keptResults) {
        String results = keptResults > 0
                ? java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(keptResults)
                        + " résultat(s) obtenu(s) avant l'erreur conservé(s)"
                : "résultats non enregistrés";
        String message = error instanceof OutOfMemoryError
                ? "Mémoire du backend insuffisante (OutOfMemoryError) : " + results + ". "
                        + "Réduisez la charge ou la durée du test, ou augmentez LOADPILOT_JVM_MAX_MEMORY."
                : "Erreur inattendue du serveur LoadPilot (" + error.getClass().getSimpleName() + ") : " + results + ".";
        try {
            return transactionHelper.markFailedIfActive(executionId, message);
        } catch (Throwable markError) {
            log.error("Execution {} non marquee en echec apres l'erreur inattendue : {}", executionId, markError.getClass().getName());
            return false;
        }
    }

    private void notifyTerminalStatus(PreparedExecution prepared, ExecutionStatus status) {
        NotificationType type = switch (status) {
            case SUCCESS -> NotificationType.EXECUTION_SUCCESS;
            case THRESHOLDS_EXCEEDED -> NotificationType.EXECUTION_THRESHOLDS_EXCEEDED;
            case FAILED -> NotificationType.EXECUTION_FAILED;
            case CANCELLED -> NotificationType.EXECUTION_CANCELLED;
            default -> null;
        };
        if (type == null) {
            return;
        }
        String origin = prepared.scheduleId() != null ? " (planification)" : "";
        String title = "Exécution " + statusLabel(status) + origin;
        String message = "Le scénario \"" + prepared.scenarioName() + "\" a terminé son exécution avec le statut "
                + status + ".";
        notificationService.create(prepared.triggeredByAppUserId(), type, title, message, prepared.executionId(), prepared.scheduleId());
    }

    /** Notification de fin d'une execution planifiee, ex. "Test nocturne : reussi · P95 314 ms". */
    private void notifyScheduled(PreparedExecution prepared, ExecutionStatus status) {
        try {
            scheduleNotifier.executionFinished(prepared.scheduleId(), prepared.executionId(), prepared.triggeredByAppUserId());
        } catch (Exception e) {
            log.warn("Notification de planification non envoyee pour l'execution {} : {}", prepared.executionId(), e.getClass().getSimpleName());
            notifyTerminalStatus(prepared, status);
        }
    }

    /** Nom de l'utilisateur connecte (preferred_username) ; null hors requete authentifiee. */
    private static String currentUsername() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : null;
    }

    private static String statusLabel(ExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> "réussie";
            case THRESHOLDS_EXCEEDED -> "terminée : seuils dépassés";
            case FAILED -> "échouée";
            case CANCELLED -> "annulée";
            default -> status.toString();
        };
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionDetailResponse getById(UUID id) {
        return getById(id, true);
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionDetailResponse getById(UUID id, boolean includeResults) {
        Execution execution = executionRepository.findByIdWithScenarioAndApplication(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + id));

        String applicationBaseUrl = execution.getScenario().getApplication().getUrl();
        List<ExecutionStepResultResponse> resultResponses = List.of();
        if (includeResults) {
            try (ResultLoadGuard.Reservation r = resultLoadGuard.reserve(ResultLoadGuard.Load.DETAIL_WITH_RESULTS,
                    executionStepResultRepository.countAllByExecutionId(id))) {
                resultResponses = executionStepResultRepository.findByExecutionIdOrderByTimestampAsc(id).stream()
                        .map(r2 -> executionStepResultMapper.toResponse(r2, applicationBaseUrl))
                        .toList();
            }
        }

        ExecutionResponse base = executionMapper.toResponse(execution);
        return new ExecutionDetailResponse(base.id(), base.scenarioId(), base.scenarioName(), base.startedAt(),
                base.finishedAt(), base.status(), base.virtualUsers(), base.rampUpSeconds(), base.durationSeconds(),
                base.iterations(), base.totalSteps(), base.successfulSteps(), base.failedSteps(),
                base.duration(), base.errorMessage(), resultResponses, execution.getRequestDetailMode(),
                execution.isDetailSampleCapReached(), requestDetailRepository.countByExecutionIdAndKind(id, "FAILURE"),
                requestDetailRepository.countByExecutionIdAndKind(id, "SAMPLE"), reportSnapshots.retention(execution, false, false),
                toDouble(ThroughputCalculator.compute(executed(execution), execution.activeDurationMs())),
                toDouble(PerformanceAggregates.errorRate(failed(execution), executed(execution))), base.loadMode(), base.stress());
    }

    private static int executed(Execution e) {
        return (e.getSuccessfulSteps() == null ? 0 : e.getSuccessfulSteps()) + failed(e);
    }

    private static int failed(Execution e) {
        return e.getFailedSteps() == null ? 0 : e.getFailedSteps();
    }

    private static Double toDouble(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionStatusResponse getStatus(UUID id) {
        Execution execution = executionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + id));
        // Execution en cours ou en pause : compteurs en memoire du moteur.
        boolean started = execution.getStatus() == ExecutionStatus.RUNNING || execution.getStatus() == ExecutionStatus.PAUSED;
        RunningExecutionHandle handle = started ? registry.get(id) : null;
        LiveStats.Snapshot live = handle != null && handle.liveStats() != null ? handle.liveStats().snapshot() : null;
        long pausedMs = pausedDurationMs(execution, Instant.now());
        // Temps de travail depuis le demarrage des utilisateurs virtuels, pauses exclues.
        Long activeElapsedMs = null;
        if (started) {
            activeElapsedMs = live != null
                    ? handle.activeNanosSince(handle.liveStats().startedNanos()) / 1_000_000L
                    : Math.max(0L, Duration.between(execution.getStartedAt(), Instant.now()).toMillis() - pausedMs);
        } else if (execution.getDuration() != null) {
            activeElapsedMs = Math.max(0L, execution.getDuration() - pausedMs);
        }
        int vus = execution.getVirtualUsers() == null ? 1 : execution.getVirtualUsers();
        boolean stress = execution.getLoadMode() == com.loadpilot.backend.enums.LoadMode.STRESS;
        // Iterations prevues : VU x iterations ; sans duree ni iterations, une par VU ; duree seule : inconnu.
        // Mode stress : fin inconnue a l'avance (rupture ou limite de LoadPilot), pas de progression.
        Long planned = null;
        if (stress) planned = null;
        else if (execution.getIterations() != null) planned = (long) vus * execution.getIterations();
        else if (execution.getDurationSeconds() == null) planned = (long) vus;
        return new ExecutionStatusResponse(
                execution.getId().toString(),
                execution.getStatus(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                execution.getDuration(),
                execution.getVirtualUsers(),
                live != null ? (int) live.requests() : execution.getSuccessfulSteps() + execution.getFailedSteps(),
                live != null ? (int) live.successes() : execution.getSuccessfulSteps(),
                live != null ? (int) live.failures() : execution.getFailedSteps(),
                stress ? null : computeProgressPercent(execution, live, planned, activeElapsedMs),
                execution.getScenario() != null ? execution.getScenario().getId().toString() : null,
                execution.getScenario() != null ? execution.getScenario().getName() : null,
                execution.getRampUpSeconds(),
                execution.getDurationSeconds(),
                execution.getIterations(),
                planned,
                execution.getErrorMessage(),
                live,
                execution.getPausedAt(),
                pausedMs,
                activeElapsedMs,
                resources(execution, handle),
                injector(handle, live),
                execution.getLoadMode(),
                stressLive(handle),
                com.loadpilot.backend.mapper.MapperSupport.executionStress(execution));
    }

    /** Montee du test de stress en cours ; null hors mode stress ou tant que le moteur n'a pas demarre. */
    static com.loadpilot.backend.dto.response.StressLiveResponse stressLive(RunningExecutionHandle handle) {
        StressRamp ramp = handle != null ? handle.stressRamp() : null;
        LiveStats stats = handle != null ? handle.liveStats() : null;
        if (ramp == null || stats == null) return null;
        long elapsed = stats.elapsedSeconds();
        int warmupEnd = StressSettings.WARMUP_SECONDS;
        int baselineEnd = StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS;
        StressRamp.Limit holding = ramp.holding();
        String phase = holding != null ? "HOLD" : elapsed < warmupEnd ? "WARMUP" : elapsed < baselineEnd ? "BASELINE" : "RAMP";
        return new com.loadpilot.backend.dto.response.StressLiveResponse(phase, elapsed, warmupEnd, baselineEnd, ramp.offeredRps(),
                ramp.peakOfferedRps(), ramp.virtualUsers(), handle.stressBaselineP95Ms(), holding != null ? holding.name() : null,
                ramp.limits().maxVirtualUsers(), ramp.limits().maxRps(), ramp.limits().maxDurationSeconds(),
                ramp.limits().rampRpsPerSecond());
    }

    /** Mesures de LoadPilot pendant le test ; null hors execution en cours. */
    static com.loadpilot.backend.dto.response.InjectorStatusResponse injector(RunningExecutionHandle handle, LiveStats.Snapshot live) {
        com.loadpilot.backend.service.monitoring.InjectorTrack track = handle != null ? handle.injector() : null;
        com.loadpilot.backend.service.monitoring.InjectorTrack.Sample latest = track != null ? track.latest() : null;
        if (latest == null) return null;
        return new com.loadpilot.backend.dto.response.InjectorStatusResponse(latest.processCpuPercent(), track.cpuMaxPercent(),
                latest.machineCpuPercent(), latest.heapUsedMb(), latest.heapMaxMb(), live != null ? live.activeVirtualUsers() : 0,
                latest.platformThreads(), track.cpuWarningPercent(), track.overloaded(), track.warning());
    }

    /** CPU / RAM de l'application testee : mesures en memoire pendant le test, resume enregistre ensuite. */
    static com.loadpilot.backend.dto.response.ExecutionResourcesResponse resources(Execution execution, RunningExecutionHandle handle) {
        ResourceTrack track = handle != null ? handle.resources() : null;
        if (track != null) {
            List<ResourceTrack.Sample> samples = track.samples();
            ResourceTrack.Sample latest = samples.isEmpty() ? null : samples.get(samples.size() - 1);
            List<ResourceTrack.Sample> recent = samples.subList(Math.max(0, samples.size() - 24), samples.size());
            List<Double> cpu = samples.stream().map(ResourceTrack.Sample::cpuPercent).filter(java.util.Objects::nonNull).toList();
            List<Double> mem = samples.stream().map(ResourceTrack.Sample::memoryMb).filter(java.util.Objects::nonNull).toList();
            return new com.loadpilot.backend.dto.response.ExecutionResourcesResponse(track.source(), track.intervalSeconds(),
                    latest == null ? null : latest.cpuPercent(), latest == null ? null : latest.memoryMb(),
                    cpu.isEmpty() ? null : Math.round(cpu.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0,
                    cpu.isEmpty() ? null : cpu.stream().mapToDouble(Double::doubleValue).max().orElse(0),
                    mem.isEmpty() ? null : mem.stream().mapToDouble(Double::doubleValue).max().orElse(0),
                    track.message(),
                    recent.stream().map(ResourceTrack.Sample::cpuPercent).toList(),
                    recent.stream().map(ResourceTrack.Sample::memoryMb).toList());
        }
        if (execution.getCpuAvgPercent() == null && execution.getMemoryMaxMb() == null && execution.getMonitoringMessage() == null
                && execution.getResourceJson() == null) {
            return null;
        }
        return new com.loadpilot.backend.dto.response.ExecutionResourcesResponse(null, 0, null, null, execution.getCpuAvgPercent(),
                execution.getCpuMaxPercent(), execution.getMemoryMaxMb(), execution.getMonitoringMessage(), List.of(), List.of());
    }

    private ExecutionHistoryResponse withPrevious(ExecutionHistoryResponse row) {
        if (row.status() == null || row.status().isActive() || row.startedAt() == null || row.scenarioId() == null) return row;
        List<Execution> previous = executionRepository.findPreviousFinishedByScenario(UUID.fromString(row.scenarioId()), row.startedAt(),
                PageRequest.of(0, 1));
        if (previous.isEmpty()) return row;
        return row.withPrevious(previous.get(0).getP95Ms(), previous.get(0).getStartedAt());
    }

    private final java.util.Set<String> statsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Calcule en arriere-plan le resume des executions terminees qui n'en ont pas encore. */
    private void backfillStatsInBackground(List<ExecutionHistoryResponse> rows) {
        for (ExecutionHistoryResponse row : rows) {
            if (row.p95Ms() != null || row.status() == null || row.status().isActive()) continue;
            if ((row.successfulSteps() == null ? 0 : row.successfulSteps()) + (row.failedSteps() == null ? 0 : row.failedSteps()) == 0) continue;
            if (!statsInFlight.add(row.id())) continue;
            Thread.ofVirtual().name("stats-" + row.id()).start(() -> {
                try {
                    executionStatsService.compute(UUID.fromString(row.id()));
                } catch (Exception e) {
                    log.debug("Resume non calcule en arriere-plan pour {} : {}", row.id(), e.getClass().getSimpleName());
                } finally {
                    statsInFlight.remove(row.id());
                }
            });
        }
    }

    /** Pauses terminees + pause en cours eventuelle (ms). */
    static long pausedDurationMs(Execution execution, Instant now) {
        long total = execution.getPausedDurationMs() == null ? 0L : execution.getPausedDurationMs();
        if (execution.getPausedAt() != null) {
            total += Math.max(0L, Duration.between(execution.getPausedAt(), now).toMillis());
        }
        return total;
    }

    /** Progression en % : temps ecoule / duree, ou iterations faites / prevues ; null si inconnue. */
    private Integer computeProgressPercent(Execution execution, LiveStats.Snapshot live, Long plannedIterations,
            Long activeElapsedMs) {
        return switch (execution.getStatus()) {
            case SUCCESS, THRESHOLDS_EXCEEDED, FAILED, CANCELLED -> 100;
            case QUEUED -> 0;
            case RUNNING, PAUSED -> {
                if (execution.getDurationSeconds() != null) {
                    // Temps de travail (pauses exclues).
                    long elapsedMs = activeElapsedMs != null ? activeElapsedMs : 0L;
                    long totalMs = execution.getDurationSeconds() * 1000L;
                    yield (int) Math.min(99, Math.max(0, totalMs <= 0 ? 99 : (elapsedMs * 100) / totalMs));
                }
                if (live != null && plannedIterations != null && plannedIterations > 0) {
                    yield (int) Math.min(99, (live.completedIterations() * 100) / plannedIterations);
                }
                yield null;
            }
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExecutionResponse> list() {
        return executionMapper.toResponseList(executionRepository.findAll(NEWEST_FIRST));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExecutionResponse> listByScenario(UUID scenarioId) {
        if (!scenarioRepository.existsById(scenarioId)) {
            throw new ResourceNotFoundException("Scenario introuvable : " + scenarioId);
        }
        return executionMapper.toResponseList(executionRepository.findByScenarioId(scenarioId, NEWEST_FIRST));
    }

    /** Annule une execution : en attente, passee directement a CANCELLED ; en cours, utilisateurs virtuels interrompus. */
    @Override
    public ExecutionResponse cancel(UUID id) {
        try {
            String cancelledBy = currentUsername();
            CancellationAttempt attempt = transactionHelper.attemptCancellation(id, cancelledBy);

            if (attempt.outcome() == CancellationOutcome.ALREADY_TERMINAL) {
                throw new ConflictException("Impossible d'annuler cette execution : elle est deja terminee (statut "
                        + attempt.execution().getStatus() + ").");
            }

            RunningExecutionHandle handle = registry.get(id);

            if (attempt.outcome() == CancellationOutcome.CANCELLED_WHILE_QUEUED) {
                if (handle != null) {
                    handle.requestCancellationBy(cancelledBy);
                }
                auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.CANCEL, AuditModule.EXECUTION), id)
                        .description("Exécution annulée avant son démarrage"));
                return executionMapper.toResponse(attempt.execution());
            }

            if (handle == null) {
                throw new ConflictException(
                        "Impossible d'annuler : aucune execution active trouvee en memoire pour cet identifiant "
                                + "(le serveur a peut-etre redemarre depuis le lancement).");
            }
            handle.requestCancellationBy(cancelledBy);
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.CANCEL, AuditModule.EXECUTION), id)
                    .description("Annulation demandée pendant l’exécution"));
            return executionMapper.toResponse(attempt.execution());
        } catch (RuntimeException e) {
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.CANCEL, AuditModule.EXECUTION), id)
                    .description("Annulation impossible").failed(e));
            throw e;
        }
    }

    /** Met une execution en pause : les utilisateurs virtuels cessent d'envoyer de nouvelles requetes. */
    @Override
    public ExecutionResponse pause(UUID id) {
        try {
            ExecutionStatus current = transactionHelper.currentStatus(id);
            if (current != ExecutionStatus.RUNNING) {
                throw new ConflictException(ExecutionTransactionHelper.pauseRefusal(current));
            }
            RunningExecutionHandle handle = registry.get(id);
            if (handle == null) {
                throw new ConflictException(ExecutionTransactionHelper.NOT_ACTIVE_IN_MEMORY);
            }
            if (handle.isCancelled()) {
                throw new ConflictException(ExecutionTransactionHelper.PAUSE_FINISHED);
            }
            // Une pause fausserait la comparaison a la baseline : un test de stress se poursuit ou s'annule.
            if (handle.isStressMode()) {
                throw new ConflictException(STRESS_NOT_PAUSABLE);
            }
            Execution paused = transactionHelper.markPaused(id);
            handle.pause();
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.PAUSE, AuditModule.EXECUTION), id)
                    .description("Exécution mise en pause"));
            return executionMapper.toResponse(paused);
        } catch (RuntimeException e) {
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.PAUSE, AuditModule.EXECUTION), id)
                    .description("Mise en pause impossible").failed(e));
            throw e;
        }
    }

    /** Reprend une execution en pause. */
    @Override
    public ExecutionResponse resume(UUID id) {
        try {
            ExecutionStatus current = transactionHelper.currentStatus(id);
            if (current != ExecutionStatus.PAUSED) {
                throw new ConflictException(ExecutionTransactionHelper.resumeRefusal(current));
            }
            RunningExecutionHandle handle = registry.get(id);
            if (handle == null) {
                throw new ConflictException(ExecutionTransactionHelper.NOT_ACTIVE_IN_MEMORY);
            }
            Execution resumed = transactionHelper.markResumed(id);
            handle.resume();
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.RESUME, AuditModule.EXECUTION), id)
                    .description("Exécution reprise"));
            return executionMapper.toResponse(resumed);
        } catch (RuntimeException e) {
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.RESUME, AuditModule.EXECUTION), id)
                    .description("Reprise impossible").failed(e));
            throw e;
        }
    }

    /** Annule une execution restee trop longtemps en pause. */
    public void cancelForPauseTimeout(UUID id, String reason) {
        RunningExecutionHandle handle = registry.get(id);
        if (handle == null) {
            return;
        }
        CancellationAttempt attempt = transactionHelper.attemptCancellation(id);
        if (attempt.outcome() == CancellationOutcome.ALREADY_TERMINAL) {
            return;
        }
        handle.requestCancellation(reason);
        auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.CANCEL, AuditModule.EXECUTION), id)
                .system("Pause trop longue")
                .description("Exécution annulée automatiquement après une pause trop longue"));
    }

    /** Arret par le frein automatique sur taux d'erreurs. */
    public void cancelForErrorRate(UUID id, String reason) {
        cancelAutomatically(id, reason, "Frein sur taux d’erreurs");
    }

    /** Description d'audit de fin ; mode stress : resultat de la montee, borne a la colonne (500 caracteres). */
    static String finishDescription(Execution finalized) {
        String text = "Exécution terminée : " + AuditTargets.statusLabel(finalized.getStatus())
                + (finalized.getStressMessage() != null ? " — " + finalized.getStressMessage() : "");
        return text.length() > 500 ? text.substring(0, 499) + "…" : text;
    }

    /**
     * Mode stress : rupture confirmee ou baseline inexploitable -> fin du test (requetes en cours terminees), decision
     * gardee pour le verdict ; LoadPilot sature avant la cible -> annulation automatique « non concluant ».
     */
    private void onStressDecision(PreparedExecution prepared, RunningExecutionHandle handle, BreakpointWatcher.Outcome decision) {
        switch (decision) {
            case BreakpointWatcher.Ruptured ruptured -> {
                log.info("Execution {} : {}", prepared.executionId(), ruptured.breakpoint().message());
                handle.stressOutcome(decision);
                handle.requestStop();
            }
            case BreakpointWatcher.BaselineUnusable unusable -> {
                log.warn("Execution {} : {}", prepared.executionId(), unusable.reason());
                handle.stressOutcome(decision);
                handle.requestStop();
            }
            case BreakpointWatcher.Inconclusive inconclusive -> {
                handle.stressOutcome(decision);
                cancelAutomatically(prepared.executionId(), inconclusive.reason(), "Injecteur saturé (test de stress)");
            }
        }
    }

    /** Arret par le garde-fou memoire. */
    public void cancelForMemoryLimit(UUID id, String reason) {
        cancelAutomatically(id, reason, "Limite de résultats en mémoire");
    }

    /** Arret automatique d'une execution en cours ; la premiere demande d'annulation l'emporte. */
    private void cancelAutomatically(UUID id, String reason, String actor) {
        RunningExecutionHandle handle = registry.get(id);
        if (handle == null || handle.isCancelled()) {
            return;
        }
        CancellationAttempt attempt = transactionHelper.attemptCancellation(id);
        if (attempt.outcome() == CancellationOutcome.ALREADY_TERMINAL) {
            return;
        }
        if (!handle.requestCancellation(reason)) {
            return;
        }
        log.info("Execution {} arretee automatiquement ({})", id, actor);
        auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.CANCEL, AuditModule.EXECUTION), id)
                .system(actor)
                .description(reason));
    }

    @Override
    public ExecutionResponse retry(UUID id, UUID triggeredByAppUserId) {
        try {
            UUID scenarioId = executionRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + id))
                    .getScenario().getId();
            ExecutionResponse response = doExecute(scenarioId, triggeredByAppUserId, null);
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.RETRY, AuditModule.EXECUTION), id)
                    .description("Relancée : nouvelle exécution créée"));
            return response;
        } catch (RuntimeException e) {
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.RETRY, AuditModule.EXECUTION), id)
                    .description("Relance impossible").failed(e));
            throw e;
        }
    }

    /** Historique pagine, filtre et trie en base. */
    @Override
    @Transactional(readOnly = true)
    public PagedResponse<ExecutionHistoryResponse> getHistory(
            ExecutionHistoryFilterRequest filter, int page, int size, Sort sort) {
        Specification<Execution> specification = ExecutionSpecifications.withFilters(filter);
        Page<Execution> result = executionRepository.findAll(specification, PageRequest.of(page, size, sort));
        return new PagedResponse<>(
                executionMapper.toHistoryResponseList(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /** Page de la liste des executions : filtres, pagination et compteurs par statut. */
    @Override
    @Transactional(readOnly = true)
    public ExecutionPageResponse getPage(ExecutionHistoryFilterRequest filter, int page, int size, Sort sort) {
        PagedResponse<ExecutionHistoryResponse> rawRows = getHistory(filter, page, size, sort);
        backfillStatsInBackground(rawRows.content());
        // Ecart de P95 avec l'execution terminee precedente du meme scenario.
        PagedResponse<ExecutionHistoryResponse> rows = new PagedResponse<>(rawRows.content().stream().map(this::withPrevious).toList(),
                rawRows.page(), rawRows.size(), rawRows.totalElements(), rawRows.totalPages());
        java.util.Map<ExecutionStatus, Long> byStatus = new java.util.EnumMap<>(ExecutionStatus.class);
        for (ExecutionStatus status : ExecutionStatus.values()) {
            boolean excluded = (filter.status() != null && filter.status() != status) || (filter.finishedOnly() && status.isActive());
            byStatus.put(status, excluded ? 0L : executionRepository.count(ExecutionSpecifications.withFilters(
                    new ExecutionHistoryFilterRequest(status, filter.scenarioId(), filter.applicationId(),
                            filter.dateFrom(), filter.dateTo(), filter.search(), filter.finishedOnly()))));
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new ExecutionPageResponse(rows.content(), rows.page(), rows.size(), rows.totalElements(), rows.totalPages(),
                new ExecutionPageResponse.Counts(total, byStatus.get(ExecutionStatus.QUEUED), byStatus.get(ExecutionStatus.RUNNING),
                        byStatus.get(ExecutionStatus.PAUSED), byStatus.get(ExecutionStatus.SUCCESS),
                        byStatus.get(ExecutionStatus.FAILED), byStatus.get(ExecutionStatus.CANCELLED),
                        byStatus.get(ExecutionStatus.THRESHOLDS_EXCEEDED)));
    }

    /** Rapport statistique d'une execution, calcule a partir de ses resultats. */
    @Override
    @Transactional(readOnly = true)
    public ExecutionReportResponse getReport(UUID id) {
        Execution execution = executionRepository.findByIdWithScenarioAndApplication(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + id));

        // Detail des requetes supprime : rapport fige.
        if (ReportSnapshots.purged(execution)) {
            java.util.Optional<com.loadpilot.backend.service.report.ReportSnapshot> frozen = reportSnapshots.read(execution);
            if (frozen.isPresent()) {
                com.loadpilot.backend.service.report.ReportSnapshot s = frozen.get();
                return report(execution, s.pausedDurationMs(), s.statistics(), s.steps(), s.errors(), s.thresholds(),
                        reportSnapshots.retention(execution, true, s.errorsTruncated()));
            }
        }

        try (ResultLoadGuard.Reservation r = resultLoadGuard.reserve(ResultLoadGuard.Load.REPORT,
                executionStepResultRepository.countAllByExecutionId(id))) {
            List<ExecutionStepResult> results = executionStepResultRepository.findByExecutionIdOrderByTimestampAsc(id);
            // Debit calcule sur le temps de travail (pauses exclues).
            long pausedMs = pausedDurationMs(execution, Instant.now());
            Long activeDurationMs = execution.getDuration() != null ? Math.max(0L, execution.getDuration() - pausedMs) : null;
            ExecutionStatistics statistics = performanceStatisticsService.computeOverallStatistics(results, activeDurationMs);
            List<StepReportEntry> steps = performanceStatisticsService.computeStepBreakdown(results);
            List<ReportErrorEntry> errors = performanceStatisticsService.extractErrors(results);
            return report(execution, pausedMs, statistics, steps, errors, thresholds(execution, results, statistics),
                    reportSnapshots.retention(execution, false, false));
        }
    }

    private static ExecutionReportResponse report(Execution execution, long pausedMs, ExecutionStatistics statistics,
            List<StepReportEntry> steps, List<ReportErrorEntry> errors, ExecutionReportResponse.Thresholds thresholds,
            com.loadpilot.backend.dto.response.ResultsRetentionResponse retention) {
        return new ExecutionReportResponse(
                execution.getId().toString(),
                execution.getScenario().getId().toString(),
                execution.getScenario().getName(),
                execution.getScenario().getApplication().getId().toString(),
                execution.getScenario().getApplication().getName(),
                execution.getStatus(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                execution.getDuration(),
                execution.getErrorMessage(),
                execution.getVirtualUsers(),
                execution.getRampUpSeconds(),
                execution.getDurationSeconds(),
                execution.getIterations(),
                com.loadpilot.backend.mapper.MapperSupport.appUserDisplayName(execution.getTriggeredBy()),
                pausedMs,
                statistics,
                steps,
                errors,
                thresholds,
                retention,
                execution.getLoadMode(),
                com.loadpilot.backend.mapper.MapperSupport.executionStress(execution));
    }

    /** Seuils copies au lancement et leur resultat ; null pour une execution sans seuils. */
    static ExecutionReportResponse.Thresholds thresholds(Execution execution, List<ExecutionStepResult> results, ExecutionStatistics statistics) {
        if (execution.getMaxErrorRatePercent() == null || execution.getStatus() == null || execution.getStatus().isActive()) return null;
        long failures = results.stream().filter(r -> !Boolean.TRUE.equals(r.getSuccess())).count();
        double errorRate = com.loadpilot.backend.service.execution.ExecutionVerdict.roundedRate(failures, results.size());
        double exactRate = results.isEmpty() ? 0.0 : failures * 100.0 / results.size();
        Long p95 = statistics == null ? null : statistics.p95();
        Long maxP95 = execution.getMaxP95Ms();
        return new ExecutionReportResponse.Thresholds(execution.getMaxErrorRatePercent(), errorRate,
                exactRate <= execution.getMaxErrorRatePercent(), maxP95, p95, maxP95 == null || p95 == null ? null : p95 <= maxP95);
    }


    /** Audit du lancement : la cible est le scenario ; pour une planification, l'acteur est "Systeme". */
    private AuditEvent launchAudit(AuditEvent event, UUID scenarioId, UUID scheduleId) {
        String name = scenarioRepository.findById(scenarioId).map(s -> s.getName()).orElse(null);
        event.target(AuditTargetType.SCENARIO, scenarioId, name != null ? name : "Scénario introuvable");
        if (scheduleId != null) {
            event.system("Planification");
        }
        return event;
    }
}
