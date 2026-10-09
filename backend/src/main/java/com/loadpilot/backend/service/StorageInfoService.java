package com.loadpilot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.StorageInfoResponse;
import com.loadpilot.backend.dto.response.StorageInfoResponse.Purge;
import com.loadpilot.backend.service.impl.DashboardRules;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * Taille de la base et bilan des purges automatiques (Parametres > Systeme). Bilans enregistres par
 * ResultsRetentionJob et AuditRetentionJob a chaque passage ; prochain passage calcule depuis leur
 * expression cron (heure du serveur).
 */
@Service
@RequiredArgsConstructor
public class StorageInfoService {

    private final JdbcTemplate jdbc;
    private final AppSettingsService settings;
    private final ObjectMapper objectMapper;

    @Value("${app.results.purge-cron:0 45 3 * * *}")
    private String resultsCron;

    @Value("${app.audit.purge-cron:0 30 3 * * *}")
    private String auditCron;

    public StorageInfoResponse info() {
        return info(Instant.now());
    }

    StorageInfoResponse info(Instant now) {
        return new StorageInfoResponse(databaseSize(), resultsPurge(now), auditPurge(now));
    }

    /** Taille de la base PostgreSQL ; null ailleurs (ex. H2 des tests). */
    Long databaseSize() {
        try {
            return jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Purge resultsPurge(Instant now) {
        JsonNode last = read(AppSettingsService.RESULTS_LAST_PURGE);
        Instant next = nextRun(resultsCron, now);
        if (last == null) return new Purge(null, null, null, next);
        long executions = last.path("executions").asLong(0);
        long results = last.path("results").asLong(0);
        long detailExecutions = last.path("detailExecutions").asLong(0);
        long details = last.path("requestDetails").asLong(0);
        String summary;
        if (executions == 0 && detailExecutions == 0) {
            summary = "0 requête supprimée";
        } else {
            StringBuilder s = new StringBuilder();
            if (executions > 0) {
                s.append(plural(results, "requête supprimée", "requêtes supprimées")).append(" (").append(plural(executions, "exécution", "exécutions")).append(")");
            }
            if (detailExecutions > 0) {
                if (!s.isEmpty()) s.append(" ; ");
                s.append("en-têtes et corps supprimés (").append(plural(detailExecutions, "exécution", "exécutions")).append(")");
            }
            summary = s.toString();
        }
        return new Purge(instant(last), results + details, summary, next);
    }

    private Purge auditPurge(Instant now) {
        JsonNode last = read(AppSettingsService.AUDIT_LAST_PURGE);
        Instant next = nextRun(auditCron, now);
        if (last == null) return new Purge(null, null, null, next);
        long deleted = last.path("deleted").asLong(0);
        return new Purge(instant(last), deleted, plural(deleted, "entrée supprimée", "entrées supprimées"), next);
    }

    private JsonNode read(String key) {
        return settings.get(key).map(v -> {
            try {
                return objectMapper.readTree(v);
            } catch (Exception e) {
                return null;
            }
        }).orElse(null);
    }

    private static Instant instant(JsonNode last) {
        try {
            return Instant.parse(last.path("at").asText());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Prochain declenchement d'une expression cron Spring ; null si desactivee ("-") ou invalide. */
    static Instant nextRun(String cron, Instant now) {
        if (cron == null || cron.isBlank() || "-".equals(cron.trim())) return null;
        try {
            ZonedDateTime next = CronExpression.parse(cron.trim()).next(now.atZone(ZoneId.systemDefault()));
            return next == null ? null : next.toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String plural(long n, String one, String many) {
        return DashboardRules.format(n) + " " + (n > 1 ? many : one);
    }
}
