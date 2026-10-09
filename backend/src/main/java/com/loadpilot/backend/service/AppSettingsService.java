package com.loadpilot.backend.service;

import com.loadpilot.backend.entity.AppSetting;
import com.loadpilot.backend.repository.AppSettingRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reglages de LoadPilot stockes en base (table app_setting), lus tres souvent
 * (chaque enregistrement d'audit) : valeurs gardees en memoire et rafraichies a
 * chaque ecriture. Stockage bas niveau, sans journalisation (voir
 * AuditSettingsService pour les reglages du journal, qui eux sont audites).
 */
@Service
@RequiredArgsConstructor
public class AppSettingsService {

    public static final String AUDIT_LOG_READS = "audit.log-reads";
    public static final String AUDIT_RETENTION_DAYS = "audit.retention-days";
    public static final String SECURITY_EVENTS_CURSOR = "audit.security-events.cursor";
    public static final String RESULTS_RETENTION_DAYS = "results.retention-days";
    public static final String REQUEST_DETAILS_RETENTION_DAYS = "results.request-details-retention-days";
    /** Bilan JSON de la derniere purge (date, elements supprimes), voir StorageInfoService. */
    public static final String RESULTS_LAST_PURGE = "results.last-purge";
    public static final String AUDIT_LAST_PURGE = "audit.last-purge";
    public static final String DEFAULT_MAX_ERROR_RATE = "thresholds.default-max-error-rate";
    public static final String DEFAULT_MAX_P95_MS = "thresholds.default-max-p95-ms";
    public static final String REGRESSION_PERCENT = "thresholds.regression-percent";
    /** Charge proposee aux nouveaux scenarios (Parametres > Systeme > Charge par defaut) ; "" = vide. */
    public static final String LOAD_VIRTUAL_USERS = "load.virtual-users";
    public static final String LOAD_THINK_TIME_MS = "load.think-time-ms";
    public static final String LOAD_RAMP_UP_SECONDS = "load.ramp-up-seconds";
    public static final String LOAD_DURATION_SECONDS = "load.duration-seconds";
    public static final String LOAD_ITERATIONS = "load.iterations";
    public static final String LOAD_TARGET_RPS = "load.target-rps";

    public static final boolean DEFAULT_LOG_READS = false;
    public static final int DEFAULT_RETENTION_DAYS = 365;
    public static final int DEFAULT_RESULTS_RETENTION_DAYS = 90;
    public static final int DEFAULT_REQUEST_DETAILS_RETENTION_DAYS = 30;
    public static final double DEFAULT_DEFAULT_MAX_ERROR_RATE = 1.0;
    /** 0 = aucun seuil de P95 propose. */
    public static final long DEFAULT_DEFAULT_MAX_P95_MS = 0;
    public static final double DEFAULT_REGRESSION_PERCENT = 20.0;

    private final AppSettingRepository repository;
    /** Valeur en cache (Optional.empty() = reglage absent en base). */
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public Optional<String> get(String key) {
        return cache.computeIfAbsent(key, k -> repository.findById(k).map(AppSetting::getValue));
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return get(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return get(key).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        return get(key).map(v -> {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    public double getDouble(String key, double defaultValue) {
        return get(key).map(v -> {
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /** Entier facultatif : absent = defaultValue, "" = null (champ vide), illisible = defaultValue. */
    public Integer getOptionalInt(String key, Integer defaultValue) {
        Optional<String> raw = get(key);
        if (raw.isEmpty()) return defaultValue;
        String v = raw.get().trim();
        if (v.isEmpty()) return null;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional
    public void set(String key, String value, String updatedBy) {
        AppSetting setting = repository.findById(key).orElseGet(() -> {
            AppSetting created = new AppSetting();
            created.setKey(key);
            return created;
        });
        setting.setValue(value);
        setting.setUpdatedAt(Instant.now());
        setting.setUpdatedBy(updatedBy);
        repository.save(setting);
        cache.put(key, Optional.ofNullable(value));
    }

    public boolean logReads() {
        return getBoolean(AUDIT_LOG_READS, DEFAULT_LOG_READS);
    }

    public int retentionDays() {
        return getInt(AUDIT_RETENTION_DAYS, DEFAULT_RETENTION_DAYS);
    }

    /** Duree de conservation des resultats detailles (une ligne par requete), en jours. */
    public int resultsRetentionDays() {
        return getInt(RESULTS_RETENTION_DAYS, DEFAULT_RESULTS_RETENTION_DAYS);
    }

    /** Duree de conservation du detail des requetes (en-tetes et corps), en jours. */
    public int requestDetailsRetentionDays() {
        return getInt(REQUEST_DETAILS_RETENTION_DAYS, DEFAULT_REQUEST_DETAILS_RETENTION_DAYS);
    }

    /** Taux d'erreurs maximal (%) propose pour un nouveau scenario. */
    public double defaultMaxErrorRate() {
        return getDouble(DEFAULT_MAX_ERROR_RATE, DEFAULT_DEFAULT_MAX_ERROR_RATE);
    }

    /** P95 maximal (ms) propose pour un nouveau scenario ; 0 = aucun. */
    public long defaultMaxP95Ms() {
        return getLong(DEFAULT_MAX_P95_MS, DEFAULT_DEFAULT_MAX_P95_MS);
    }

    /** Hausse du P95 (%) au-dela de laquelle une execution est une regression (Dashboard, listes, notifications). */
    public double regressionPercent() {
        return getDouble(REGRESSION_PERCENT, DEFAULT_REGRESSION_PERCENT);
    }

    /** Pour les tests : oublie le cache (la base reste la source de verite). */
    public void clearCache() {
        cache.clear();
    }
}
