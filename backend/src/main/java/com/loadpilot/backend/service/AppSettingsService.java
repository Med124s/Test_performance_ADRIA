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

    public static final boolean DEFAULT_LOG_READS = false;
    public static final int DEFAULT_RETENTION_DAYS = 365;

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

    /** Pour les tests : oublie le cache (la base reste la source de verite). */
    public void clearCache() {
        cache.clear();
    }
}
