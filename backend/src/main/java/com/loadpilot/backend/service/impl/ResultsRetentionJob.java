package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.AppSettingsService;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purge quotidienne du detail des executions (durees reglables dans Parametres > Systeme) :
 *  - resultats (une ligne par requete, et les metriques qui en sont tirees) des executions
 *    terminees depuis plus de N jours (90 par defaut), avec leurs en-tetes et corps ;
 *  - en-tetes et corps des requetes conservees, apres M jours (30 par defaut).
 * Jamais purgees : executions en cours (ou dont l'enregistrement n'est pas fini) et executions
 * « Conserver ». Le rapport fige est calcule avant toute suppression (sinon l'execution est
 * laissee intacte). Par lots : quelques executions a la fois, une transaction par execution
 * (ligne verrouillee : l'option « Conserver » ne peut pas changer pendant la purge), lignes
 * supprimees par paquets. Une entree « Système » au journal d'audit si quelque chose a ete supprime.
 */
@Service
@RequiredArgsConstructor
public class ResultsRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ResultsRetentionJob.class);
    static final int EXECUTIONS_PER_BATCH = 20;
    static final int ROWS_PER_DELETE = 5000;

    private final ExecutionRepository executionRepository;
    private final ReportSnapshotService snapshotService;
    private final AppSettingsService settings;
    private final AuditLogService auditLogService;
    private final RunningExecutionRegistry registry;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /** Bilan d'une purge. */
    public record Outcome(int resultExecutions, long results, int detailExecutions, long requestDetails, int skipped) {
        public boolean deletedSomething() {
            return results + requestDetails > 0 || resultExecutions + detailExecutions > 0;
        }
    }

    @Scheduled(cron = "${app.results.purge-cron:0 45 3 * * *}")
    public void scheduledPurge() {
        try {
            purge(Instant.now());
        } catch (Throwable e) {
            // Throwable : un manque de memoire (rapport fige d'une grosse execution calcule avant sa purge)
            // est journalise en erreur ; la purge reprend a son prochain passage.
            if (e instanceof Error) log.error("Purge des resultats detailles interrompue : {}", e.getClass().getSimpleName());
            else log.warn("Purge des resultats detailles interrompue : {}", e.getClass().getSimpleName());
        }
    }

    public Outcome purge(Instant now) {
        int resultsDays = settings.resultsRetentionDays();
        int detailsDays = settings.requestDetailsRetentionDays();
        int resultExecutions = 0;
        int detailExecutions = 0;
        int skipped = 0;
        long results = 0;
        long details = 0;

        // 1. Resultats complets (et leurs en-tetes / corps) au-dela de la duree de conservation.
        Instant resultsCutoff = now.minus(Duration.ofDays(resultsDays));
        java.util.Set<UUID> tried = new java.util.HashSet<>();
        List<UUID> batch;
        while (!(batch = next(executionRepository.findResultsToPurge(resultsCutoff, PageRequest.of(0, EXECUTIONS_PER_BATCH + tried.size())), tried)).isEmpty()) {
            for (UUID id : batch) {
                if (registry.get(id) != null || !snapshotService.ensure(id)) {
                    skipped++;
                    continue;
                }
                long[] deleted = purgeResults(id, now);
                if (deleted == null) {
                    skipped++;
                    continue;
                }
                resultExecutions++;
                results += deleted[0];
                details += deleted[1];
            }
        }

        // 2. En-tetes et corps seuls, au-dela de leur propre duree (plus courte).
        Instant detailsCutoff = now.minus(Duration.ofDays(detailsDays));
        tried.clear();
        while (!(batch = next(executionRepository.findRequestDetailsToPurge(detailsCutoff, PageRequest.of(0, EXECUTIONS_PER_BATCH + tried.size())), tried)).isEmpty()) {
            for (UUID id : batch) {
                if (registry.get(id) != null) {
                    skipped++;
                    continue;
                }
                Long deleted = purgeRequestDetails(id, now);
                if (deleted == null) {
                    skipped++;
                    continue;
                }
                detailExecutions++;
                details += deleted;
            }
        }

        Outcome outcome = new Outcome(resultExecutions, results, detailExecutions, details, skipped);
        // Bilan de ce passage (Parametres > Systeme : derniere purge), meme si rien n'a ete supprime.
        settings.set(AppSettingsService.RESULTS_LAST_PURGE, "{\"at\":\"" + now + "\",\"executions\":" + resultExecutions
                + ",\"results\":" + results + ",\"detailExecutions\":" + detailExecutions + ",\"requestDetails\":" + details + "}", "system");
        if (outcome.deletedSomething()) {
            log.info("Purge des resultats detailles : {} execution(s), {} resultat(s) ; detail des requetes de {} execution(s), {} ligne(s)",
                    resultExecutions, results, detailExecutions, details);
            auditLogService.record(AuditEvent.of(AuditAction.PURGE, AuditModule.EXECUTION)
                    .system("Purge automatique quotidienne")
                    .description(describe(outcome, resultsDays, detailsDays)));
        }
        if (skipped > 0) log.warn("Purge des resultats detailles : {} execution(s) laissee(s) intacte(s)", skipped);
        return outcome;
    }

    /** Lot suivant, sans les executions deja essayees pendant cette purge (laissees intactes). */
    private static List<UUID> next(List<UUID> candidates, java.util.Set<UUID> tried) {
        return candidates.stream().filter(tried::add).toList();
    }

    static String describe(Outcome o, int resultsDays, int detailsDays) {
        StringBuilder text = new StringBuilder();
        if (o.resultExecutions() > 0) {
            text.append("Résultats détaillés de ").append(plural(o.resultExecutions(), "exécution", "exécutions"))
                    .append(" de plus de ").append(resultsDays).append(" jours supprimés (")
                    .append(plural(o.results(), "requête", "requêtes")).append(")");
        }
        if (o.detailExecutions() > 0) {
            if (!text.isEmpty()) text.append(" ; ");
            text.append("en-têtes et corps des requêtes de ").append(plural(o.detailExecutions(), "exécution", "exécutions"))
                    .append(" de plus de ").append(detailsDays).append(" jours supprimés");
        }
        if (!text.isEmpty()) text.setCharAt(0, Character.toUpperCase(text.charAt(0)));
        return text + ". Rapports conservés.";
    }

    private static String plural(long n, String one, String many) {
        return String.format(java.util.Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ') + " " + (n > 1 ? many : one);
    }

    /** {resultats, en-tetes/corps} supprimes, ou null si l'execution ne doit plus etre purgee. */
    long[] purgeResults(UUID id, Instant now) {
        return transactions.execute(status -> {
            if (!lockedAndPurgeable(id, false)) return null;
            long details = deleteInChunks("execution_request_detail", id);
            long results = deleteInChunks("execution_step_result", id);
            deleteInChunks("metric", id);
            jdbc.update("UPDATE execution SET results_purged_at = ?, request_details_purged_at = COALESCE(request_details_purged_at, ?) WHERE id = ?",
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), id);
            return new long[] {results, details};
        });
    }

    /** En-tetes et corps supprimes, ou null si l'execution ne doit plus etre purgee. */
    Long purgeRequestDetails(UUID id, Instant now) {
        return transactions.execute(status -> {
            if (!lockedAndPurgeable(id, true)) return null;
            long details = deleteInChunks("execution_request_detail", id);
            jdbc.update("UPDATE execution SET request_details_purged_at = ? WHERE id = ?", java.sql.Timestamp.from(now), id);
            return details;
        });
    }

    /** Verrouille la ligne et reverifie : terminee, non conservee, pas deja purgee. */
    private boolean lockedAndPurgeable(UUID id, boolean detailsOnly) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, keep_results, results_purged_at, request_details_purged_at FROM execution WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) return false;
        Map<String, Object> row = rows.get(0);
        ExecutionStatus status = ExecutionStatus.valueOf(String.valueOf(row.get("status")));
        if (status.isActive() || Boolean.TRUE.equals(row.get("keep_results")) || row.get("results_purged_at") != null) return false;
        return !detailsOnly || row.get("request_details_purged_at") == null;
    }

    private long deleteInChunks(String table, UUID executionId) {
        long total = 0;
        int deleted;
        do {
            deleted = jdbc.update("DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                    + " WHERE execution_id = ? FETCH FIRST " + ROWS_PER_DELETE + " ROWS ONLY)", executionId);
            total += deleted;
        } while (deleted == ROWS_PER_DELETE);
        return total;
    }
}
