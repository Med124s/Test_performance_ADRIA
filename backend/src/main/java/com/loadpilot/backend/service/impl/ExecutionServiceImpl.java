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
import com.loadpilot.backend.service.execution.CancellationAttempt;
import com.loadpilot.backend.service.execution.CancellationOutcome;
import com.loadpilot.backend.service.execution.ExecutionEngine;
import com.loadpilot.backend.service.execution.PreparedExecution;
import com.loadpilot.backend.service.execution.RequestDetailRecorder;
import com.loadpilot.backend.service.execution.RunningExecutionHandle;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.loadpilot.backend.service.execution.ScenarioExecutionOutcome;
import com.loadpilot.backend.service.monitoring.MonitoringConfig;
import com.loadpilot.backend.service.monitoring.ResourceMonitor;
import com.loadpilot.backend.service.monitoring.ResourceTrack;
import com.loadpilot.backend.service.report.CsvUtils;
import com.loadpilot.backend.service.report.ExecutionStatistics;
import com.loadpilot.backend.service.report.PerformanceStatisticsService;
import com.loadpilot.backend.service.report.ReportErrorEntry;
import com.loadpilot.backend.service.report.StepReportEntry;
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

/**
 * Orchestration d'une execution (P0-A : desormais ASYNCHRONE - voir
 * ExecutionTransactionHelper pour le decoupage transactionnel court, et
 * RunningExecutionRegistry/RunningExecutionHandle pour l'annulation reelle).
 *
 * POST /api/executions ne bloque plus jamais pendant la duree reelle d'un
 * test de charge : l'Execution est creee QUEUED, puis la tache reelle
 * (potentiellement longue, plusieurs utilisateurs virtuels concurrents) est
 * soumise a un executeur de threads virtuels (voir config.ExecutorConfig)
 * et suit son cours en arriere-plan. Le suivi se fait via GET /{id} ou
 * GET /{id}/status (voir ExecutionController).
 *
 * Audit (Phase 12, inchange) : pour EXECUTE/RETRY, le resultat d'audit
 * reflete le vrai ExecutionStatus final (SUCCESS/FAILED), enregistre par la
 * tache asynchrone elle-meme une fois l'execution reellement terminee.
 */
@Service
@RequiredArgsConstructor
public class ExecutionServiceImpl implements ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionServiceImpl.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "startedAt");

    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository executionStepResultRepository;
    private final ScenarioRepository scenarioRepository;
    private final ExecutionMapper executionMapper;
    private final ExecutionStepResultMapper executionStepResultMapper;
    private final ExecutionEngine executionEngine;
    private final ExecutionTransactionHelper transactionHelper;
    private final MetricGenerationService metricGenerationService;
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
    /** Fourni a la demande : ReportSnapshotService depend lui-meme de ce service (getReport). */
    private final org.springframework.beans.factory.ObjectProvider<ReportSnapshotService> reportSnapshotService;
    private final ScheduleNotifier scheduleNotifier;

    /** Plafond memoire du detail des requetes par execution (Mo) - voir RequestDetailRecorder. */
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
        try {
            // P0-B : le nombre de VUs doit etre connu AVANT de reserver de la
            // capacite (voir RunningExecutionRegistry) - lecture separee et
            // volontairement minimale (une seule PK) ; prepareAndStart relira
            // le Scenario juste apres dans sa propre transaction courte -
            // aucun etat n'est jamais transporte entre les deux, juste un
            // entier.
            virtualUsers = scenarioRepository.findById(scenarioId)
                    .map(Scenario::getVirtualUsers)
                    .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + scenarioId));

            // Refus deterministe AVANT toute ecriture si une limite globale
            // serait depassee (prompt P0-B, section 7 ; P1-B, section 44 :
            // un declenchement planifie respecte EXACTEMENT la meme limite,
            // jamais un contournement pour les executions issues d'une
            // ScheduledExecution) - jamais une Execution creee puis
            // silencieusement bloquee.
            registry.reserveCapacity(virtualUsers);
            capacityReserved = true;

            // Transaction courte : valide le scenario/ses steps, cree
            // l'Execution QUEUED avec les parametres de charge figes.
            PreparedExecution prepared = transactionHelper.prepareAndStart(scenarioId, triggeredByAppUserId, scheduleId);

            RunningExecutionHandle handle = registry.registerReserved(prepared.executionId(), virtualUsers);
            // Detail des requetes (echecs + echantillon), plafond memoire global par execution.
            handle.detailRecorder(new RequestDetailRecorder(prepared.requestDetailMode(), requestDetailMaxMemoryMb * 1024L * 1024L));
            // A partir d'ici, c'est registry.unregister() (voir runAsync,
            // bloc finally) qui a la responsabilite exclusive de liberer
            // cette capacite - jamais les deux a la fois.
            capacityReserved = false;
            // Monitoring CPU / RAM de l'application testee (optionnel, jamais bloquant).
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
            // La reservation de capacite a reussi mais aucune Execution n'a
            // finalement pu etre creee/enregistree (ex : le Scenario n'a plus
            // d'etapes) - sans cette liberation explicite, la capacite
            // resterait perdue indefiniment alors qu'aucune execution
            // reelle ne l'occupe.
            if (capacityReserved) {
                registry.releaseReservationWithoutRegistering(virtualUsers);
            }
            auditLogService.record(launchAudit(AuditEvent.of(AuditAction.EXECUTE, AuditModule.EXECUTION), scenarioId, scheduleId)
                    .description("Exécution non lancée").failed(e));
            throw e;
        }
    }

    /**
     * Travail REEL, execute hors du thread HTTP appelant (voir execute()).
     * Ne leve jamais vers l'appelant (il n'y en a pas) : toute exception
     * inattendue est journalisee et auditee ici meme.
     */
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
        UUID finishedId = null;
        try {
            ScenarioExecutionOutcome outcome;
            // P0-B : markRunning() peut desormais echouer a transitionner
            // (retourne false) si un cancel() concurrent a deja fait passer
            // cette Execution QUEUED -> CANCELLED sous verrou pendant que
            // cette tache demarrait a peine (voir
            // ExecutionTransactionHelper.attemptCancellation/markRunning) -
            // dans ce cas comme dans celui, deja existant, ou le handle est
            // deja marque annule, AUCUNE requete HTTP reelle ne doit etre
            // envoyee pour une execution que l'utilisateur croit deja
            // annulee avant son demarrage.
            if (handle.isCancelled() || !transactionHelper.markRunning(prepared.executionId())) {
                outcome = new ScenarioExecutionOutcome(List.of(), ExecutionStatus.FAILED, null);
            } else {
                if (monitoring != null) monitoringSession = resourceMonitor.start(monitoring, handle.resources());
                outcome = executionEngine.execute(prepared.steps(), prepared.applicationBaseUrl(), prepared.auth(),
                        prepared.loadSpec(), handle, prepared.variableSources());
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
            Execution finalized = transactionHelper.finalizeExecution(prepared.executionId(), outcome, handle.isCancelled(),
                    handle.cancellationReason(), handle.detailRecorder() != null && handle.detailRecorder().sampleCapReached(),
                    handle.cancelledBy());

            // Transaction courte separee, APRES finalisation (Phase 10) - voir
            // commentaire historique : un echec de generation de Metric ne
            // doit jamais alterer le statut deja committe.
            try {
                metricGenerationService.generateForExecution(finalized.getId());
            } catch (Exception e) {
                log.warn("Generation des metriques echouee pour l'execution {} : {}",
                        finalized.getId(), e.getClass().getSimpleName());
            }

            AuditResult auditResult = finalized.getStatus() == ExecutionStatus.SUCCESS
                    ? AuditResult.SUCCESS : AuditResult.FAILURE;
            // Action automatique : acteur « Système ».
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.FINISH, AuditModule.EXECUTION), finalized.getId())
                    .system(prepared.scheduleId() != null ? "Exécution planifiée" : "Fin d’exécution")
                    .result(auditResult)
                    .description("Exécution terminée : " + AuditTargets.statusLabel(finalized.getStatus())));
            // Execution planifiee : notification propre a la planification, envoyee plus bas une fois le P95 calcule.
            if (prepared.scheduleId() == null) notifyTerminalStatus(prepared, finalized.getStatus());

            // Chronologie pour l'analyse dans le temps (best-effort, apres la notification : jamais bloquant ;
            // la chronologie est capturee a la fin de l'execution, pas au moment de cette ecriture).
            try {
                transactionHelper.saveTimeline(finalized.getId(), timeline);
            } catch (Exception e) {
                log.warn("Chronologie non enregistree pour l'execution {} : {}", finalized.getId(), e.getClass().getSimpleName());
            }

            // Resume pour le Dashboard (P95, debit, P95 par etape), calcule une fois ; best-effort.
            try {
                executionStatsService.compute(finalized.getId());
            } catch (Exception e) {
                log.warn("Resume non calcule pour l'execution {} : {}", finalized.getId(), e.getClass().getSimpleName());
            }
            finishedId = finalized.getId();
            if (prepared.scheduleId() != null) notifyScheduled(prepared, finalized.getStatus());
        } catch (RuntimeException e) {
            log.error("Echec inattendu de l'execution asynchrone {} : {}", prepared.executionId(), e.getClass().getSimpleName());
            auditLogService.record(auditTargets.onExecution(AuditEvent.of(AuditAction.FINISH, AuditModule.EXECUTION), prepared.executionId())
                    .system(prepared.scheduleId() != null ? "Exécution planifiée" : "Fin d’exécution")
                    .description("Exécution interrompue par une erreur inattendue").failed(e));
            notifyTerminalStatus(prepared, ExecutionStatus.FAILED);
        } finally {
            if (monitoringSession != null) monitoringSession.stop();
            registry.unregister(prepared.executionId());
        }
        // Rapport fige (sert de rapport une fois le detail purge), APRES liberation de la place :
        // ce calcul ne retarde jamais l'execution suivante. Best-effort, rattrape sinon au
        // demarrage ou juste avant la purge (voir ReportSnapshotService, ResultsRetentionJob).
        if (finishedId != null) {
            try {
                reportSnapshotService.getObject().capture(finishedId);
            } catch (Exception e) {
                log.warn("Rapport fige non enregistre pour l'execution {} : {}", finishedId, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * P1-B — notifie REELLEMENT l'utilisateur a l'origine de l'execution
     * (prepared.triggeredBy(), potentiellement null pour toute execution
     * dont l'origine n'est pas connue - jamais fabrique, voir
     * NotificationService.create qui n'emet alors simplement rien) une fois
     * un statut TERMINAL reellement atteint (jamais pour QUEUED/RUNNING).
     * "scheduleId" non-null indique une execution issue d'une
     * ScheduledExecution : uniquement reflete dans le libelle, jamais une
     * logique differente.
     */
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

    /** Fin d'une execution planifiee : « Test nocturne : réussi · P95 314 ms »... (voir ScheduleNotifier) ; repli sur la notification generique. */
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

        List<ExecutionStepResult> results = includeResults
                ? executionStepResultRepository.findByExecutionIdOrderByTimestampAsc(id) : List.of();
        String applicationBaseUrl = execution.getScenario().getApplication().getUrl();

        List<ExecutionStepResultResponse> resultResponses = results.stream()
                .map(r -> executionStepResultMapper.toResponse(r, applicationBaseUrl))
                .toList();

        ExecutionResponse base = executionMapper.toResponse(execution);
        return new ExecutionDetailResponse(base.id(), base.scenarioId(), base.scenarioName(), base.startedAt(),
                base.finishedAt(), base.status(), base.virtualUsers(), base.rampUpSeconds(), base.durationSeconds(),
                base.iterations(), base.totalSteps(), base.successfulSteps(), base.failedSteps(),
                base.duration(), base.errorMessage(), resultResponses, execution.getRequestDetailMode(),
                execution.isDetailSampleCapReached(), requestDetailRepository.countByExecutionIdAndKind(id, "FAILURE"),
                requestDetailRepository.countByExecutionIdAndKind(id, "SAMPLE"), reportSnapshots.retention(execution, false, false));
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionStatusResponse getStatus(UUID id) {
        Execution execution = executionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + id));
        // Execution en cours (ou en pause) : compteurs en memoire du moteur (aucune lecture des resultats en base).
        boolean started = execution.getStatus() == ExecutionStatus.RUNNING || execution.getStatus() == ExecutionStatus.PAUSED;
        RunningExecutionHandle handle = started ? registry.get(id) : null;
        LiveStats.Snapshot live = handle != null && handle.liveStats() != null ? handle.liveStats().snapshot() : null;
        long pausedMs = pausedDurationMs(execution, Instant.now());
        // Temps de travail : depuis le demarrage reel des VU (sinon depuis la creation), pauses exclues.
        // Pendant l'execution, temps ecoule et temps de pause viennent de la meme horloge (celle du
        // moteur) : la progression reste parfaitement figee pendant une pause.
        Long activeElapsedMs = null;
        if (started) {
            activeElapsedMs = live != null
                    ? handle.activeNanosSince(handle.liveStats().startedNanos()) / 1_000_000L
                    : Math.max(0L, Duration.between(execution.getStartedAt(), Instant.now()).toMillis() - pausedMs);
        } else if (execution.getDuration() != null) {
            activeElapsedMs = Math.max(0L, execution.getDuration() - pausedMs);
        }
        int vus = execution.getVirtualUsers() == null ? 1 : execution.getVirtualUsers();
        // Iterations prevues : VU x iterations ; sans duree ni iterations, une par VU ; duree seule : inconnu.
        Long planned = null;
        if (execution.getIterations() != null) planned = (long) vus * execution.getIterations();
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
                computeProgressPercent(execution, live, planned, activeElapsedMs),
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
                resources(execution, handle));
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

    /** Executions dont le resume est en cours de calcul en arriere-plan (jamais deux fois en meme temps). */
    private ExecutionHistoryResponse withPrevious(ExecutionHistoryResponse row) {
        if (row.status() == null || row.status().isActive() || row.startedAt() == null || row.scenarioId() == null) return row;
        List<Execution> previous = executionRepository.findPreviousFinishedByScenario(UUID.fromString(row.scenarioId()), row.startedAt(),
                PageRequest.of(0, 1));
        if (previous.isEmpty()) return row;
        return row.withPrevious(previous.get(0).getP95Ms(), previous.get(0).getStartedAt());
    }

    private final java.util.Set<String> statsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * P95 de la liste : lu dans le resume enregistre (aucun calcul pendant la requete). Executions
     * terminees anterieures sans resume : calcul lance en arriere-plan, visible au rafraichissement
     * suivant - la liste n'est jamais ralentie.
     */
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

    /**
     * Ne fabrique JAMAIS un pourcentage (prompt P0-A section 15) : calcule
     * uniquement quand reellement possible (etat terminal, ou RUNNING avec
     * une duree configuree - progression = temps ecoule / duree, jamais
     * plus de 99% avant la finalisation reelle). Sans duree configuree
     * (mode iterations ou mode 1-passe), la progression n'est pas
     * calculable honnetement pendant l'execution : null, jamais invente.
     */
    private Integer computeProgressPercent(Execution execution, LiveStats.Snapshot live, Long plannedIterations,
            Long activeElapsedMs) {
        return switch (execution.getStatus()) {
            case SUCCESS, THRESHOLDS_EXCEEDED, FAILED, CANCELLED -> 100;
            case QUEUED -> 0;
            case RUNNING, PAUSED -> {
                if (execution.getDurationSeconds() != null) {
                    // Temps de travail reel (pauses exclues) : la progression ne bouge pas pendant une pause.
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

    /**
     * Annulation REELLE (P0-A, course corrigee en P0-B) :
     *
     * PROBLEME identifie en P0-B : l'ancienne version lisait le statut PUIS
     * ecrivait CANCELLED dans une transaction separee, sans jamais
     * revalider entre les deux - une execution ayant reellement demarre
     * (voire deja terminee en SUCCESS/FAILED) entre cette lecture et cette
     * ecriture voyait son statut reel incorrectement ecrase par CANCELLED
     * ("course cancel + completion naturelle").
     * CHOIX : la decision QUEUED/RUNNING/terminal ET l'eventuelle ecriture
     * sont desormais faites ATOMIQUEMENT, sous verrou pessimiste sur cette
     * seule ligne, par ExecutionTransactionHelper.attemptCancellation - voir
     * sa Javadoc pour le detail complet.
     *
     * - QUEUED : rien n'a jamais reellement commence, transition ecrite
     *   directement par attemptCancellation.
     * - RUNNING : interruption reelle des utilisateurs virtuels en cours
     *   (RunningExecutionHandle.requestCancellation - interrompt les
     *   requetes HTTP en vol via Future.cancel(true)) ; le statut CANCELLED
     *   est ecrit par la tache asynchrone elle-meme une fois qu'elle a
     *   reellement fini de s'arreter (jamais ecrit ici en double, pour ne
     *   jamais avoir deux ecrivains concurrents sur la meme ligne).
     * - Sinon (deja terminee, y compris deja CANCELLED par un appel
     *   concurrent) : 409 - une seconde annulation sur une meme execution
     *   n'est jamais silencieusement acceptee une deuxieme fois, mais l'etat
     *   final du systeme reste toujours coherent et deterministe (voir
     *   rapport P0-B, section Cancellation, pour la discussion complete de
     *   ce choix face a une idempotence "parfaite").
     */
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

            // CANCELLATION_REQUESTED_WHILE_RUNNING
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

    /**
     * Pause REELLE : statut PAUSED en base (sous verrou), puis les utilisateurs virtuels
     * cessent d'envoyer de nouvelles requetes (les requetes en vol recoivent leur reponse).
     * 409 si l'execution n'est pas en cours dans ce processus.
     */
    @Override
    public ExecutionResponse pause(UUID id) {
        try {
            // Statut reel d'abord (404 clair si introuvable, 409 clair si terminee / en attente / deja en pause).
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

    /** Reprise : les utilisateurs virtuels repartent exactement la ou ils s'etaient arretes. */
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

    /**
     * Annule une execution restee en pause trop longtemps (voir PausedExecutionWatchdog) :
     * meme chemin qu'une annulation utilisateur, avec un motif explicite.
     */
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

    @Override
    public ExecutionResponse retry(UUID id, UUID triggeredByAppUserId) {
        try {
            UUID scenarioId = executionRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + id))
                    .getScenario().getId();
            // execute() emet deja son propre audit EXECUTE pour la nouvelle
            // execution QUEUED qu'il cree : cet audit RETRY est un fait
            // distinct et reel ("une relance a ete demandee depuis
            // l'execution id"), pas un doublon.
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

    /**
     * P1-A — filtrage/tri/pagination executes cote base (Specification +
     * Pageable, meme mecanisme que AuditLogServiceImpl.search) : jamais de
     * chargement complet de la table suivi d'un filtrage Java. Le fetch
     * join scenario/application (voir ExecutionSpecifications) evite tout
     * N+1 lors du mapping de toute une page de resultats.
     */
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

    /**
     * Liste de la page Executions : memes filtres que l'historique (statut, scenario, application,
     * recherche), pagination serveur, et compteurs par statut calcules avec ces memes filtres (une
     * requete COUNT par statut, aucune si le filtre de statut l'exclut d'office). Jamais auditee :
     * la page se rafraichit pendant qu'une execution tourne.
     */
    @Override
    @Transactional(readOnly = true)
    public ExecutionPageResponse getPage(ExecutionHistoryFilterRequest filter, int page, int size, Sort sort) {
        PagedResponse<ExecutionHistoryResponse> rawRows = getHistory(filter, page, size, sort);
        backfillStatsInBackground(rawRows.content());
        // Ecart de P95 : execution terminee precedente du meme scenario (une requete courte par ligne).
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

    /**
     * P1-A — construit le rapport complet (statistiques/percentiles reels,
     * agregation par step, erreurs) a la demande depuis
     * ExecutionStepResult (voir PerformanceStatisticsService pour le choix
     * argumente de cette option). Meme autorisation que getById (lecture
     * ouverte a tout role authentifie, voir ExecutionController) - jamais
     * une nouvelle matrice de permissions.
     */
    @Override
    @Transactional(readOnly = true)
    public ExecutionReportResponse getReport(UUID id) {
        Execution execution = executionRepository.findByIdWithScenarioAndApplication(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + id));

        // Detail des requetes supprime (conservation) : rapport fige a la fin de l'execution.
        if (ReportSnapshots.purged(execution)) {
            java.util.Optional<com.loadpilot.backend.service.report.ReportSnapshot> frozen = reportSnapshots.read(execution);
            if (frozen.isPresent()) {
                com.loadpilot.backend.service.report.ReportSnapshot s = frozen.get();
                return report(execution, s.pausedDurationMs(), s.statistics(), s.steps(), s.errors(), s.thresholds(),
                        reportSnapshots.retention(execution, true, s.errorsTruncated()));
            }
        }

        List<ExecutionStepResult> results = executionStepResultRepository.findByExecutionIdOrderByTimestampAsc(id);
        // Debit calcule sur le temps de travail reel : le temps passe en pause est exclu.
        long pausedMs = pausedDurationMs(execution, Instant.now());
        Long activeDurationMs = execution.getDuration() != null ? Math.max(0L, execution.getDuration() - pausedMs) : null;
        ExecutionStatistics statistics = performanceStatisticsService.computeOverallStatistics(results, activeDurationMs);
        List<StepReportEntry> steps = performanceStatisticsService.computeStepBreakdown(results);
        List<ReportErrorEntry> errors = performanceStatisticsService.extractErrors(results);
        return report(execution, pausedMs, statistics, steps, errors, thresholds(execution, results, statistics),
                reportSnapshots.retention(execution, false, false));
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
                retention);
    }

    /** Seuils copies au lancement et leur resultat (meme P95 que le rapport) ; null pour une execution anterieure aux seuils. */
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

    @Override
    @Transactional(readOnly = true)
    public String exportReportCsv(UUID id) {
        return toReportCsv(getReport(id));
    }

    /**
     * P1-A (prompt section 24) — structure en PLUSIEURS sections clairement
     * separees (une ligne de titre en majuscules avant chaque bloc) : un
     * rapport melange des donnees de nature differente (identite de
     * l'execution, configuration de charge, statistiques globales,
     * percentiles, detail par step, erreurs) - un unique tableau plat les
     * aurait rendues illisibles (colonnes non alignees d'une section a
     * l'autre). Chaque section reste neanmoins un CSV RFC 4180 valide
     * (memes regles d'echappement partout, voir CsvUtils).
     */
    private String toReportCsv(ExecutionReportResponse report) {
        StringBuilder csv = new StringBuilder();

        csv.append("EXECUTION\r\n");
        csv.append("id,scenario,application,status,startedAt,finishedAt,duration_ms,paused_ms,triggeredBy,errorMessage\r\n");
        csv.append(CsvUtils.field(report.id())).append(',')
                .append(CsvUtils.field(report.scenarioName())).append(',')
                .append(CsvUtils.field(report.applicationName())).append(',')
                .append(CsvUtils.field(report.status())).append(',')
                .append(CsvUtils.field(report.startedAt())).append(',')
                .append(CsvUtils.field(report.finishedAt())).append(',')
                .append(CsvUtils.field(report.duration())).append(',')
                .append(CsvUtils.field(report.pausedDurationMs())).append(',')
                .append(CsvUtils.field(report.triggeredByUsername())).append(',')
                .append(CsvUtils.field(report.errorMessage())).append("\r\n\r\n");

        csv.append("LOAD CONFIGURATION\r\n");
        csv.append("virtualUsers,rampUpSeconds,durationSeconds,iterations\r\n");
        csv.append(CsvUtils.field(report.virtualUsers())).append(',')
                .append(CsvUtils.field(report.rampUpSeconds())).append(',')
                .append(CsvUtils.field(report.durationSeconds())).append(',')
                .append(CsvUtils.field(report.iterations())).append("\r\n\r\n");

        ExecutionStatistics stats = report.statistics();
        csv.append("STATISTICS (response times in milliseconds)\r\n");
        csv.append("totalRequests,successfulRequests,failedRequests,successRate,errorRate,min,max,avg,p50,p75,p90,p95,p99,throughput_req_per_s\r\n");
        csv.append(CsvUtils.field(stats.totalRequests())).append(',')
                .append(CsvUtils.field(stats.successfulRequests())).append(',')
                .append(CsvUtils.field(stats.failedRequests())).append(',')
                .append(CsvUtils.field(stats.successRate())).append(',')
                .append(CsvUtils.field(stats.errorRate())).append(',')
                .append(CsvUtils.field(stats.minResponseTime())).append(',')
                .append(CsvUtils.field(stats.maxResponseTime())).append(',')
                .append(CsvUtils.field(stats.avgResponseTime())).append(',')
                .append(CsvUtils.field(stats.p50())).append(',')
                .append(CsvUtils.field(stats.p75())).append(',')
                .append(CsvUtils.field(stats.p90())).append(',')
                .append(CsvUtils.field(stats.p95())).append(',')
                .append(CsvUtils.field(stats.p99())).append(',')
                .append(CsvUtils.field(stats.throughput())).append("\r\n\r\n");

        csv.append("STEPS\r\n");
        csv.append("step,method,url,total,success,failed,errorRate,min,avg,max,p95,p99\r\n");
        for (StepReportEntry step : report.steps()) {
            csv.append(CsvUtils.field(step.stepName())).append(',')
                    .append(CsvUtils.field(step.method())).append(',')
                    .append(CsvUtils.field(step.url())).append(',')
                    .append(CsvUtils.field(step.total())).append(',')
                    .append(CsvUtils.field(step.success())).append(',')
                    .append(CsvUtils.field(step.failed())).append(',')
                    .append(CsvUtils.field(step.errorRate())).append(',')
                    .append(CsvUtils.field(step.minResponseTime())).append(',')
                    .append(CsvUtils.field(step.avgResponseTime())).append(',')
                    .append(CsvUtils.field(step.maxResponseTime())).append(',')
                    .append(CsvUtils.field(step.p95())).append(',')
                    .append(CsvUtils.field(step.p99())).append("\r\n");
        }
        csv.append("\r\n");

        csv.append("ERRORS\r\n");
        csv.append("step,method,url,httpStatus,error,timestamp\r\n");
        for (ReportErrorEntry error : report.errors()) {
            csv.append(CsvUtils.field(error.stepName())).append(',')
                    .append(CsvUtils.field(error.method())).append(',')
                    .append(CsvUtils.field(error.url())).append(',')
                    .append(CsvUtils.field(error.httpStatus())).append(',')
                    .append(CsvUtils.field(error.error())).append(',')
                    .append(CsvUtils.field(error.timestamp())).append("\r\n");
        }

        return csv.toString();
    }

    /** Lancement refuse : la cible est le scenario (aucune execution creee) ; planification -> acteur Systeme. */
    private AuditEvent launchAudit(AuditEvent event, UUID scenarioId, UUID scheduleId) {
        String name = scenarioRepository.findById(scenarioId).map(s -> s.getName()).orElse(null);
        event.target(AuditTargetType.SCENARIO, scenarioId, name != null ? name : "Scénario introuvable");
        if (scheduleId != null) {
            event.system("Planification");
        }
        return event;
    }
}
