package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.StressSeriesRequest;
import com.loadpilot.backend.dto.response.StressSeriesResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.StressSeries;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.LoadMode;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.enums.StressOutcome;
import com.loadpilot.backend.enums.StressSeriesStatus;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.MapperSupport;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StressSeriesRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.ExecutionService;
import com.loadpilot.backend.service.NotificationService;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.execution.ExecutionStatusReason;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.loadpilot.backend.service.execution.StressSeriesSummary;
import com.loadpilot.backend.service.execution.StressSettings;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Serie d'essais de stress : le meme scenario en mode stress lance 3 ou 5 fois de suite, avec une pause entre deux
 * essais pendant laquelle le serveur reste reserve a la serie, puis un point de rupture consolide (StressSeriesSummary).
 * Un essai non concluant (echoue, annule, LoadPilot sature) arrete la serie ; le consolide porte sur les essais termines.
 */
@Service
public class StressSeriesService implements ApplicationRunner {

    static final String STRESS_ONLY = "Une série d'essais ne se lance que sur un scénario en mode « Point de rupture ».";
    static final String RUNS_ALLOWED = "Nombre d'essais d'une série : 3 ou 5 (un seul essai : lancement normal).";
    static final String CANCELLED_DURING_PAUSE = "La série d'essais a été annulée : l'essai suivant n'est pas lancé.";
    static final String RESTART_REASON = "Le serveur LoadPilot a redémarré pendant la série.";
    private static final int STOP_REASON_MAX = 500;
    private static final List<ExecutionStatus> ACTIVE = PerformanceAggregates.ACTIVE;

    private static final Logger log = LoggerFactory.getLogger(StressSeriesService.class);

    private final StressSeriesRepository seriesRepository;
    private final ExecutionRepository executionRepository;
    private final ScenarioRepository scenarioRepository;
    private final AppUserRepository appUserRepository;
    private final ExecutionService executionService;
    private final RunningExecutionRegistry registry;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;
    private final int pauseSeconds;
    /** Pauses en cours : lancement prevu de l'essai suivant, par serie. */
    private final Map<UUID, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().daemon().name("stress-series").unstarted(r));
    /** Fin d'un essai, annulation et lancement suivant d'une meme serie : jamais en meme temps. */
    private final Object lock = new Object();

    public StressSeriesService(StressSeriesRepository seriesRepository, ExecutionRepository executionRepository,
            ScenarioRepository scenarioRepository, AppUserRepository appUserRepository, ExecutionService executionService,
            RunningExecutionRegistry registry, AuditLogService auditLogService, NotificationService notificationService,
            PlatformTransactionManager transactionManager,
            @Value("${app.stress.series-pause-seconds:" + StressSettings.SERIES_PAUSE_SECONDS + "}") int pauseSeconds) {
        this.seriesRepository = seriesRepository;
        this.executionRepository = executionRepository;
        this.scenarioRepository = scenarioRepository;
        this.appUserRepository = appUserRepository;
        this.executionService = executionService;
        this.registry = registry;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.tx = new TransactionTemplate(transactionManager);
        this.pauseSeconds = pauseSeconds;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** Au demarrage : une serie restee en cours (serveur arrete pendant la serie) est interrompue. */
    @Override
    public void run(ApplicationArguments args) {
        List<UUID> orphaned = tx.execute(status -> seriesRepository.findByStatus(StressSeriesStatus.RUNNING).stream()
                .map(StressSeries::getId).toList());
        for (UUID id : orphaned) {
            tx.executeWithoutResult(status -> seriesRepository.findById(id)
                    .ifPresent(s -> finish(s, StressSeriesStatus.INTERRUPTED, RESTART_REASON)));
            notifyFinished(id);
        }
        if (!orphaned.isEmpty()) log.warn("Recuperation au demarrage : {} serie(s) d'essais de stress interrompue(s).", orphaned.size());
    }

    /** Lance la serie : cree la serie puis son premier essai ; rien n'est garde si l'essai ne peut pas demarrer. */
    public StressSeriesResponse start(StressSeriesRequest request, UUID triggeredByAppUserId) {
        int runs = request.runs();
        if (runs < 2 || !StressSettings.SERIES_RUNS.contains(runs)) throw new BadRequestException(RUNS_ALLOWED);
        StressSeries created = tx.execute(status -> {
            Scenario scenario = scenarioRepository.findById(request.scenarioId())
                    .orElseThrow(() -> new ResourceNotFoundException("Scenario introuvable : " + request.scenarioId()));
            if (scenario.getLoadMode() != LoadMode.STRESS) throw new BadRequestException(STRESS_ONLY);
            return seriesRepository.save(StressSeries.builder()
                    .scenario(scenario)
                    .requestedRuns(runs)
                    .status(StressSeriesStatus.RUNNING)
                    .triggeredBy(triggeredByAppUserId != null ? appUserRepository.getReferenceById(triggeredByAppUserId) : null)
                    .startedAt(Instant.now())
                    .build());
        });
        try {
            executionService.executeSeriesRun(request.scenarioId(), triggeredByAppUserId, created.getId(), 1);
        } catch (RuntimeException e) {
            tx.executeWithoutResult(status -> seriesRepository.deleteById(created.getId()));
            throw e;
        }
        auditLogService.record(AuditEvent.of(AuditAction.EXECUTE, AuditModule.EXECUTION)
                .target(AuditTargetType.SCENARIO, request.scenarioId(), created.getScenario().getName())
                .description("Série de " + runs + " essais de stress lancée"));
        return get(created.getId());
    }

    /**
     * Fin d'un essai (appele avant la liberation du serveur) : true si l'essai suivant doit etre lance apres la pause,
     * false si la serie s'arrete (derniere essai, essai non concluant, serie annulee).
     */
    boolean onRunFinished(UUID seriesId, UUID executionId) {
        boolean finished;
        synchronized (lock) {
            Boolean next = tx.execute(status -> {
                StressSeries series = seriesRepository.findById(seriesId).orElse(null);
                if (series == null || series.getStatus() != StressSeriesStatus.RUNNING) return null;
                Execution run = executionRepository.findById(executionId)
                        .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));
                int index = run.getSeriesIndex() != null ? run.getSeriesIndex() : 1;
                if (!conclusive(run)) {
                    finish(series, StressSeriesStatus.INTERRUPTED, interruptionReason(run, index, series.getRequestedRuns()));
                    return false;
                }
                if (index >= series.getRequestedRuns()) {
                    finish(series, StressSeriesStatus.COMPLETED, null);
                    return false;
                }
                series.setNextRunAt(Instant.now().plusSeconds(pauseSeconds));
                return true;
            });
            if (Boolean.TRUE.equals(next)) return true;
            finished = next != null;
        }
        if (finished) notifyFinished(seriesId);
        return false;
    }

    /** Pause avant l'essai suivant (reservation deja gardee par le registre). */
    void scheduleNext(UUID seriesId) {
        pending.put(seriesId, scheduler.schedule(() -> launchNext(seriesId), pauseSeconds, TimeUnit.SECONDS));
    }

    private record Next(UUID scenarioId, UUID triggeredByAppUserId, int index, int requestedRuns) {
    }

    /** Fin de la pause : essai suivant, ou liberation du serveur si la serie a ete annulee entre-temps. */
    void launchNext(UUID seriesId) {
        pending.remove(seriesId);
        Next next;
        synchronized (lock) {
            next = tx.execute(status -> {
                StressSeries series = seriesRepository.findById(seriesId).orElse(null);
                if (series == null || series.getStatus() != StressSeriesStatus.RUNNING) return null;
                series.setNextRunAt(null);
                int index = (int) executionRepository.countBySeriesId(seriesId) + 1;
                return new Next(series.getScenario().getId(), series.getTriggeredBy() != null ? series.getTriggeredBy().getId() : null,
                        index, series.getRequestedRuns());
            });
        }
        if (next == null) {
            registry.releaseHeld(seriesId);
            return;
        }
        try {
            executionService.executeSeriesRun(next.scenarioId(), next.triggeredByAppUserId(), seriesId, next.index());
        } catch (RuntimeException e) {
            registry.releaseHeld(seriesId);
            interrupt(seriesId, "Essai " + next.index() + "/" + next.requestedRuns() + " non lancé : " + e.getMessage());
        }
    }

    /** Arrete une serie en cours (erreur inattendue, essai impossible a lancer) ; sans effet si elle est deja terminee. */
    void interrupt(UUID seriesId, String reason) {
        boolean finished;
        synchronized (lock) {
            finished = Boolean.TRUE.equals(tx.execute(status -> {
                StressSeries series = seriesRepository.findById(seriesId).orElse(null);
                if (series == null || series.getStatus() != StressSeriesStatus.RUNNING) return false;
                finish(series, StressSeriesStatus.INTERRUPTED, reason);
                return true;
            }));
        }
        cancelPause(seriesId);
        if (finished) notifyFinished(seriesId);
    }

    /** Annulation par un utilisateur : essai en cours annule, ou pause interrompue et serveur libere. */
    public StressSeriesResponse cancel(UUID seriesId, String username) {
        UUID activeRun;
        synchronized (lock) {
            activeRun = tx.execute(status -> {
                StressSeries series = seriesRepository.findById(seriesId)
                        .orElseThrow(() -> new ResourceNotFoundException("Serie d'essais introuvable : " + seriesId));
                if (series.getStatus() != StressSeriesStatus.RUNNING) {
                    throw new ConflictException("Cette série d'essais est déjà terminée.");
                }
                finish(series, StressSeriesStatus.CANCELLED, "Annulée par " + (username != null ? username : "un utilisateur") + ".");
                return executionRepository.findBySeriesIdOrderBySeriesIndexAsc(seriesId).stream()
                        .filter(e -> ACTIVE.contains(e.getStatus())).map(Execution::getId).findFirst().orElse(null);
            });
        }
        cancelPause(seriesId);
        if (activeRun != null) {
            try {
                executionService.cancel(activeRun);
            } catch (ConflictException e) {
                // Essai termine entre-temps : rien a annuler.
            }
        }
        StressSeries series = tx.execute(status -> seriesRepository.findByIdWithScenario(seriesId).orElseThrow());
        auditLogService.record(AuditEvent.of(AuditAction.CANCEL, AuditModule.EXECUTION)
                .target(AuditTargetType.SCENARIO, series.getScenario().getId(), series.getScenario().getName())
                .description("Série d'essais de stress annulée"));
        return get(seriesId);
    }

    private void cancelPause(UUID seriesId) {
        ScheduledFuture<?> future = pending.remove(seriesId);
        if (future != null) future.cancel(false);
        registry.releaseHeld(seriesId);
    }

    public StressSeriesResponse get(UUID seriesId) {
        return tx.execute(status -> {
            StressSeries series = seriesRepository.findByIdWithScenario(seriesId)
                    .orElseThrow(() -> new ResourceNotFoundException("Serie d'essais introuvable : " + seriesId));
            List<Execution> runs = executionRepository.findBySeriesIdOrderBySeriesIndexAsc(seriesId);
            String current = runs.stream().filter(e -> ACTIVE.contains(e.getStatus())).map(e -> e.getId().toString())
                    .findFirst().orElse(null);
            return new StressSeriesResponse(series.getId().toString(), series.getScenario().getId().toString(),
                    series.getScenario().getName(), series.getScenario().getApplication().getName(), series.getRequestedRuns(),
                    series.getStatus(), series.getStopReason(), MapperSupport.appUserDisplayName(series.getTriggeredBy()),
                    series.getStartedAt(), series.getFinishedAt(), series.getNextRunAt(), pauseSeconds, current,
                    runs.stream().map(e -> new StressSeriesResponse.Run(e.getSeriesIndex() != null ? e.getSeriesIndex() : 0,
                            e.getId().toString(), e.getStatus(), e.getStartedAt(), e.getDuration(), MapperSupport.executionStress(e))).toList(),
                    summaryOf(runs));
        });
    }

    /** Rapport PDF de la serie, une fois terminee. */
    public com.loadpilot.backend.service.report.pdf.ReportPdfService.PdfFile exportPdf(UUID seriesId, java.time.ZoneId zone) {
        StressSeriesResponse series = get(seriesId);
        if (series.status() == StressSeriesStatus.RUNNING) {
            throw new ConflictException("Le rapport PDF de la série est disponible une fois la série terminée.");
        }
        byte[] pdf = new com.loadpilot.backend.service.report.pdf.SeriesPdfWriter()
                .write(new com.loadpilot.backend.service.report.pdf.SeriesPdfWriter.Data(series, zone, Instant.now()));
        String name = com.loadpilot.backend.service.report.export.ReportFileNames.of(series.scenarioName(), series.startedAt(), zone,
                "serie", "pdf");
        return new com.loadpilot.backend.service.report.pdf.ReportPdfService.PdfFile(name, pdf);
    }

    /** Point de rupture consolide sur les essais termines. */
    static StressSeriesSummary summaryOf(List<Execution> runs) {
        return StressSeriesSummary.of(runs.stream().filter(e -> !ACTIVE.contains(e.getStatus()))
                .map(e -> new StressSeriesSummary.Run(e.getStressOutcome(), e.getBreakpointOfferedRps(), e.getStressPeakOfferedRps(),
                        e.getStressRampStep()))
                .toList());
    }

    /** Essai concluant : rupture, ou aucune rupture jusqu'a une limite de LoadPilot. */
    private static boolean conclusive(Execution run) {
        return (run.getStatus() == ExecutionStatus.SUCCESS || run.getStatus() == ExecutionStatus.THRESHOLDS_EXCEEDED)
                && (run.getStressOutcome() == StressOutcome.BREAKPOINT || run.getStressOutcome() == StressOutcome.NO_BREAKPOINT);
    }

    static String interruptionReason(Execution run, int index, int requestedRuns) {
        String what = run.getStressOutcome() == StressOutcome.INCONCLUSIVE ? "non concluant"
                : run.getStatus() == ExecutionStatus.CANCELLED ? "annulé"
                : run.getStatus() == ExecutionStatus.FAILED ? "échoué" : "sans résultat de stress";
        String reason = ExecutionStatusReason.of(run);
        return truncate("Essai " + index + "/" + requestedRuns + " " + what + (reason != null && !reason.isBlank() ? " : " + reason : "."));
    }

    private void finish(StressSeries series, StressSeriesStatus status, String reason) {
        series.setStatus(status);
        series.setStopReason(reason == null ? null : truncate(reason));
        series.setFinishedAt(Instant.now());
        series.setNextRunAt(null);
    }

    private static String truncate(String text) {
        return text.length() <= STOP_REASON_MAX ? text : text.substring(0, STOP_REASON_MAX - 1) + "…";
    }

    /** Fin de serie : notification a son auteur (lien vers le dernier essai) et journal d'audit ; ne leve jamais d'exception. */
    private void notifyFinished(UUID seriesId) {
        try {
            StressSeriesResponse series = get(seriesId);
            String last = series.runs().isEmpty() ? null : series.runs().get(series.runs().size() - 1).executionId();
            StressSeries entity = tx.execute(status -> seriesRepository.findByIdWithScenario(seriesId).orElseThrow());
            UUID recipient = entity.getTriggeredBy() != null ? entity.getTriggeredBy().getId() : null;
            boolean completed = series.status() == StressSeriesStatus.COMPLETED;
            NotificationType type = series.status() == StressSeriesStatus.CANCELLED ? NotificationType.EXECUTION_CANCELLED
                    : !completed ? NotificationType.EXECUTION_FAILED
                    : series.summary().ruptures() > 0 ? NotificationType.EXECUTION_THRESHOLDS_EXCEEDED : NotificationType.EXECUTION_SUCCESS;
            String title = (completed ? "Série d'essais terminée : " : "Série d'essais arrêtée : ") + series.scenarioName();
            String message = series.summary().message() + (series.stopReason() != null ? " " + series.stopReason() : "");
            notificationService.create(recipient, type, title, message, last != null ? UUID.fromString(last) : null, null);
            auditLogService.record(AuditEvent.of(AuditAction.FINISH, AuditModule.EXECUTION)
                    .target(AuditTargetType.SCENARIO, entity.getScenario().getId(), entity.getScenario().getName())
                    .system("Série d'essais de stress")
                    .result(completed ? AuditResult.SUCCESS : AuditResult.FAILURE)
                    .description(truncate((completed ? "Série de " + series.requestedRuns() + " essais terminée : " : "Série d'essais arrêtée : ")
                            + message)));
        } catch (RuntimeException e) {
            log.warn("Fin de la serie d'essais {} non notifiee : {}", seriesId, e.getClass().getSimpleName());
        }
    }
}
