package com.loadpilot.backend.service.audit;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.service.AppSettingsService;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.RetentionOverrides;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purge automatique quotidienne du journal d'audit : supprime les entrees plus
 * anciennes que la duree de conservation (Parametres, 365 jours par defaut ; en local,
 * LOADPILOT_AUDIT_RETENTION_OVERRIDE la remplace pour tester la purge, voir RetentionOverrides).
 * C'est la SEULE suppression possible d'entrees : aucune depuis l'interface ni l'API.
 * La purge elle-meme est journalisee (acteur « Système »).
 * Transaction ouverte explicitement (TransactionTemplate) : appelee depuis scheduledPurge() dans
 * la meme classe, une annotation @Transactional serait ignoree (appel interne, sans proxy) et la
 * suppression refusee faute de transaction.
 */
@Component
@RequiredArgsConstructor
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final AuditLogRepository auditLogRepository;
    private final AppSettingsService settings;
    private final AuditLogService auditLogService;
    private final RetentionOverrides overrides;
    private final TransactionTemplate transactions;

    @Scheduled(cron = "${app.audit.purge-cron:0 30 3 * * *}")
    public void scheduledPurge() {
        try {
            purge(Instant.now());
        } catch (RuntimeException e) {
            // Rien n'est supprime (transaction annulee) ; la purge reprend a son prochain passage.
            log.error("Purge du journal d'audit interrompue : {} : {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** Supprime les entrees anterieures a (now - duree de conservation) ; renvoie leur nombre. */
    public int purge(Instant now) {
        Integer deleted = transactions.execute(status -> {
            // Duree de Parametres, ou duree de test LOADPILOT_AUDIT_RETENTION_OVERRIDE (local uniquement).
            RetentionOverrides.Retention retention = overrides.audit(settings.retentionDays());
            Instant cutoff = now.minus(retention.duration());
            int count = auditLogRepository.deleteOlderThan(cutoff);
            log.info("Purge du journal d'audit : coupure {} (plus de {}), {} entree(s) supprimee(s)", cutoff, retention.label(), count);
            if (count > 0) {
                auditLogService.record(AuditEvent.of(AuditAction.PURGE, AuditModule.SETTINGS)
                        .system("Purge automatique quotidienne")
                        .description(count + (count > 1 ? " entrées de plus de " : " entrée de plus de ") + retention.label() + " supprimée" + (count > 1 ? "s" : "")));
            }
            // Bilan de ce passage (Parametres > Systeme : derniere purge), meme si rien n'a ete supprime.
            settings.set(AppSettingsService.AUDIT_LAST_PURGE, "{\"at\":\"" + now + "\",\"deleted\":" + count + "}", "system");
            return count;
        });
        return deleted == null ? 0 : deleted;
    }
}
