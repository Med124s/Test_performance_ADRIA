package com.loadpilot.backend.service.audit;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.service.AppSettingsService;
import com.loadpilot.backend.service.AuditLogService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purge automatique quotidienne du journal d'audit : supprime les entrees plus
 * anciennes que la duree de conservation (Parametres, 365 jours par defaut).
 * C'est la SEULE suppression possible d'entrees : aucune depuis l'interface ni l'API.
 * La purge elle-meme est journalisee (acteur « Système »).
 */
@Component
@RequiredArgsConstructor
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final AuditLogRepository auditLogRepository;
    private final AppSettingsService settings;
    private final AuditLogService auditLogService;

    @Scheduled(cron = "${app.audit.purge-cron:0 30 3 * * *}")
    public void scheduledPurge() {
        purge(Instant.now());
    }

    /** Supprime les entrees anterieures a (now - duree de conservation) ; renvoie leur nombre. */
    @Transactional
    public int purge(Instant now) {
        int days = settings.retentionDays();
        Instant cutoff = now.minus(Duration.ofDays(days));
        int deleted = auditLogRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Purge du journal d'audit : {} entree(s) de plus de {} jours supprimee(s)", deleted, days);
            auditLogService.record(AuditEvent.of(AuditAction.PURGE, AuditModule.SETTINGS)
                    .system("Purge automatique quotidienne")
                    .description(deleted + (deleted > 1 ? " entrées de plus de " : " entrée de plus de ") + days + " jours supprimée" + (deleted > 1 ? "s" : "")));
        }
        // Bilan de ce passage (Parametres > Systeme : derniere purge), meme si rien n'a ete supprime.
        settings.set(com.loadpilot.backend.service.AppSettingsService.AUDIT_LAST_PURGE,
                "{\"at\":\"" + now + "\",\"deleted\":" + deleted + "}", "system");
        return deleted;
    }
}
