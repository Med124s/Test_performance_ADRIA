package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.ExecutionHistoryFilterRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.response.ExecutionDetailResponse;
import com.loadpilot.backend.dto.response.ExecutionHistoryResponse;
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
import com.loadpilot.backend.service.execution.RunningExecutionHandle;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.loadpilot.backend.service.execution.ScenarioExecutionOutcome;
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
    private final RunningExecutionRegistry registry;
    private final ExecutorService loadTestExecutor;
    private final PerformanceStatisticsService performanceStatisticsService;
    private final NotificationService notificationService;

    @Override
    public ExecutionResponse execute(ExecutionRequest request, UUID triggeredByAppUserId) {
        return doExecute(request.scenarioId(), triggeredByAppUserId, null, request);
    }

    @Override
    public ExecutionResponse executeScheduled(UUID scenarioId, UUID triggeredByAppUserId, UUID scheduleId) {
        return doExecute(scenarioId, triggeredByAppUserId, scheduleId, null);
    }

    private ExecutionResponse doExecute(UUID scenarioId, UUID triggeredByAppUserId, UUID scheduleId,
            ExecutionRequest overrides) {
        boolean capacityReserved = false;
        int virtualUsers = 0;
        try {
            // P0-B : le nombre de VUs doit etre connu AVANT de reserver de la
            // capacite (voir RunningExecutionRegistry) - lecture separee et
            // volontairement minimale (une seule PK) ; prepareAndStart relira
            // le Scenario juste apres dans sa propre transaction courte -
            // aucun etat n'est jamais transporte entre les deux, juste un
            // entier. Passage produit reel (2026-10-01) : si une surcharge
            // virtualUsers est fournie pour CETTE execution, la capacite
            // reservee reflete cette valeur reelle, jamais celle
            // (potentiellement differente) enregistree sur le Scenario.
            int scenarioVirtualUsers = scenarioRepository.findById(scenarioId)
                    .map(Scenario::getVirtualUsers)
                    .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + scenarioId));
            virtualUsers = overrides != null && overrides.virtualUsers() != null
                    ? overrides.virtualUsers() : scenarioVirtualUsers;

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
            PreparedExecution prepared = transactionHelper.prepareAndStart(scenarioId, triggeredByAppUserId, scheduleId, overrides);

            RunningExecutionHandle handle = registry.registerReserved(prepared.executionId(), virtualUsers);
            // A partir d'ici, c'est registry.unregister() (voir runAsync,
            // bloc finally) qui a la responsabilite exclusive de liberer
            // cette capacite - jamais les deux a la fois.
            capacityReserved = false;
            loadTestExecutor.submit(() -> runAsync(prepared, handle));

            Execution queued = executionRepository.findByIdWithScenarioAndApplication(prepared.executionId())
                    .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + prepared.executionId()));
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
            auditLogService.record(AuditAction.EXECUTE, AuditModule.EXECUTION, AuditResult.FAILURE,
                    "Execution failed to start for scenario " + scenarioId + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * Travail REEL, execute hors du thread HTTP appelant (voir execute()).
     * Ne leve jamais vers l'appelant (il n'y en a pas) : toute exception
     * inattendue est journalisee et auditee ici meme.
     */
    private void runAsync(PreparedExecution prepared, RunningExecutionHandle handle) {
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
                outcome = executionEngine.execute(prepared.steps(), prepared.applicationBaseUrl(),
                        prepared.loadSpec(), handle, prepared.vuVariableRows());
            }

            Execution finalized = transactionHelper.finalizeExecution(prepared.executionId(), outcome, handle.isCancelled());

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
            auditLogService.record(AuditAction.EXECUTE, AuditModule.EXECUTION, auditResult,
                    "Execution " + finalized.getId() + " finished with status " + finalized.getStatus());
            notifyTerminalStatus(prepared, finalized.getStatus());
        } catch (RuntimeException e) {
            log.error("Echec inattendu de l'execution asynchrone {} : {}", prepared.executionId(), e.getClass().getSimpleName());
            auditLogService.record(AuditAction.EXECUTE, AuditModule.EXECUTION, AuditResult.FAILURE,
                    "Execution " + prepared.executionId() + " failed unexpectedly: " + e.getClass().getSimpleName());
            notifyTerminalStatus(prepared, ExecutionStatus.FAILED);
        } finally {
            registry.unregister(prepared.executionId());
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

    private static String statusLabel(ExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> "réussie";
            case FAILED -> "échouée";
            case CANCELLED -> "annulée";
            default -> status.toString();
        };
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionDetailResponse getById(UUID id) {
        Execution execution = executionRepository.findByIdWithScenarioAndApplication(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + id));

        List<ExecutionStepResult> results = executionStepResultRepository.findByExecutionIdOrderByTimestampAsc(id);
        String applicationBaseUrl = execution.getScenario().getApplication().getUrl();

        List<ExecutionStepResultResponse> resultResponses = results.stream()
                .map(r -> executionStepResultMapper.toResponse(r, applicationBaseUrl))
                .toList();

        ExecutionResponse base = executionMapper.toResponse(execution);
        return new ExecutionDetailResponse(base.id(), base.scenarioId(), base.scenarioName(), base.startedAt(),
                base.finishedAt(), base.status(), base.virtualUsers(), base.rampUpSeconds(), base.durationSeconds(),
                base.iterations(), base.totalSteps(), base.successfulSteps(), base.failedSteps(),
                base.duration(), base.errorMessage(), resultResponses);
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionStatusResponse getStatus(UUID id) {
        Execution execution = executionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + id));
        return new ExecutionStatusResponse(
                execution.getId().toString(),
                execution.getStatus(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                execution.getDuration(),
                execution.getVirtualUsers(),
                execution.getSuccessfulSteps() + execution.getFailedSteps(),
                execution.getSuccessfulSteps(),
                execution.getFailedSteps(),
                computeProgressPercent(execution));
    }

    /**
     * Ne fabrique JAMAIS un pourcentage (prompt P0-A section 15) : calcule
     * uniquement quand reellement possible (etat terminal, ou RUNNING avec
     * une duree configuree - progression = temps ecoule / duree, jamais
     * plus de 99% avant la finalisation reelle). Sans duree configuree
     * (mode iterations ou mode 1-passe), la progression n'est pas
     * calculable honnetement pendant l'execution : null, jamais invente.
     */
    private Integer computeProgressPercent(Execution execution) {
        return switch (execution.getStatus()) {
            case SUCCESS, FAILED, CANCELLED -> 100;
            case QUEUED -> 0;
            case RUNNING -> {
                if (execution.getDurationSeconds() == null) {
                    yield null;
                }
                long elapsedMs = Duration.between(execution.getStartedAt(), Instant.now()).toMillis();
                long totalMs = execution.getDurationSeconds() * 1000L;
                yield (int) Math.min(99, Math.max(0, totalMs <= 0 ? 99 : (elapsedMs * 100) / totalMs));
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
            CancellationAttempt attempt = transactionHelper.attemptCancellation(id);

            if (attempt.outcome() == CancellationOutcome.ALREADY_TERMINAL) {
                throw new ConflictException("Impossible d'annuler cette execution : elle est deja terminee (statut "
                        + attempt.execution().getStatus() + ").");
            }

            RunningExecutionHandle handle = registry.get(id);

            if (attempt.outcome() == CancellationOutcome.CANCELLED_WHILE_QUEUED) {
                if (handle != null) {
                    handle.requestCancellation();
                }
                auditLogService.record(AuditAction.CANCEL, AuditModule.EXECUTION, AuditResult.SUCCESS,
                        "Execution cancelled before start: " + id);
                return executionMapper.toResponse(attempt.execution());
            }

            // CANCELLATION_REQUESTED_WHILE_RUNNING
            if (handle == null) {
                throw new ConflictException(
                        "Impossible d'annuler : aucune execution active trouvee en memoire pour cet identifiant "
                                + "(le serveur a peut-etre redemarre depuis le lancement).");
            }
            handle.requestCancellation();
            auditLogService.record(AuditAction.CANCEL, AuditModule.EXECUTION, AuditResult.SUCCESS,
                    "Cancellation requested for running execution: " + id);
            return executionMapper.toResponse(attempt.execution());
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.CANCEL, AuditModule.EXECUTION, AuditResult.FAILURE,
                    "Execution cancel failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
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
            ExecutionResponse response = doExecute(scenarioId, triggeredByAppUserId, null, null);
            auditLogService.record(AuditAction.RETRY, AuditModule.EXECUTION, AuditResult.SUCCESS,
                    "Execution " + id + " retried as new execution " + response.id());
            return response;
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.RETRY, AuditModule.EXECUTION, AuditResult.FAILURE,
                    "Execution retry failed for id " + id + ": " + e.getClass().getSimpleName());
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
        List<ExecutionStepResult> results = executionStepResultRepository.findByExecutionIdOrderByTimestampAsc(id);

        ExecutionStatistics statistics = performanceStatisticsService.computeOverallStatistics(results, execution.getDuration());
        List<StepReportEntry> steps = performanceStatisticsService.computeStepBreakdown(results);
        List<ReportErrorEntry> errors = performanceStatisticsService.extractErrors(results);

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
                statistics,
                steps,
                errors);
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
        csv.append("id,scenario,application,status,startedAt,finishedAt,duration_ms,triggeredBy,errorMessage\r\n");
        csv.append(CsvUtils.field(report.id())).append(',')
                .append(CsvUtils.field(report.scenarioName())).append(',')
                .append(CsvUtils.field(report.applicationName())).append(',')
                .append(CsvUtils.field(report.status())).append(',')
                .append(CsvUtils.field(report.startedAt())).append(',')
                .append(CsvUtils.field(report.finishedAt())).append(',')
                .append(CsvUtils.field(report.duration())).append(',')
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
        csv.append("totalRequests,successfulRequests,failedRequests,successRate,errorRate,min,max,avg,stdDev,p50,p75,p90,p95,p99,throughput_req_per_s\r\n");
        csv.append(CsvUtils.field(stats.totalRequests())).append(',')
                .append(CsvUtils.field(stats.successfulRequests())).append(',')
                .append(CsvUtils.field(stats.failedRequests())).append(',')
                .append(CsvUtils.field(stats.successRate())).append(',')
                .append(CsvUtils.field(stats.errorRate())).append(',')
                .append(CsvUtils.field(stats.minResponseTime())).append(',')
                .append(CsvUtils.field(stats.maxResponseTime())).append(',')
                .append(CsvUtils.field(stats.avgResponseTime())).append(',')
                .append(CsvUtils.field(stats.stdDevResponseTime())).append(',')
                .append(CsvUtils.field(stats.p50())).append(',')
                .append(CsvUtils.field(stats.p75())).append(',')
                .append(CsvUtils.field(stats.p90())).append(',')
                .append(CsvUtils.field(stats.p95())).append(',')
                .append(CsvUtils.field(stats.p99())).append(',')
                .append(CsvUtils.field(stats.throughput())).append("\r\n\r\n");

        csv.append("STEPS\r\n");
        csv.append("step,method,url,total,success,failed,errorRate,avg,min,max,stdDev,p95,p99\r\n");
        for (StepReportEntry step : report.steps()) {
            csv.append(CsvUtils.field(step.stepName())).append(',')
                    .append(CsvUtils.field(step.method())).append(',')
                    .append(CsvUtils.field(step.url())).append(',')
                    .append(CsvUtils.field(step.total())).append(',')
                    .append(CsvUtils.field(step.success())).append(',')
                    .append(CsvUtils.field(step.failed())).append(',')
                    .append(CsvUtils.field(step.errorRate())).append(',')
                    .append(CsvUtils.field(step.avgResponseTime())).append(',')
                    .append(CsvUtils.field(step.minResponseTime())).append(',')
                    .append(CsvUtils.field(step.maxResponseTime())).append(',')
                    .append(CsvUtils.field(step.stdDevResponseTime())).append(',')
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
}
