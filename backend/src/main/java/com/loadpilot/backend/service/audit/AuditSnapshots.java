package com.loadpilot.backend.service.audit;

import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.entity.Step;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Valeurs importantes d'un objet, avec leur libelle francais, prises avant et
 * apres une modification : leur difference donne l'« avant -> apres » du
 * journal d'audit (voir AuditEvent#diff). Jamais de secret (configuration
 * d'authentification, en-tetes, corps de requete ne sont pas repris).
 */
public final class AuditSnapshots {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private AuditSnapshots() {
    }

    public static Map<String, Object> of(Application a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Nom", a.getName());
        m.put("URL", a.getUrl());
        m.put("Description", shorten(a.getDescription()));
        m.put("Authentification", a.getAuthType() == null ? null : switch (a.getAuthType()) {
            case NONE -> "Aucune";
            case BEARER_TOKEN -> "Jeton Bearer";
            case API_KEY -> "Clé d’API";
            case BASIC -> "Identifiant et mot de passe (Basic)";
            default -> a.getAuthType().name();
        });
        m.put("Supervision CPU / RAM", a.getMonitoringType() == null ? null : switch (a.getMonitoringType()) {
            case NONE -> "Aucune";
            default -> a.getMonitoringType().name().charAt(0) + a.getMonitoringType().name().substring(1).toLowerCase();
        });
        return m;
    }

    public static Map<String, Object> of(Scenario s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Nom", s.getName());
        m.put("Description", shorten(s.getDescription()));
        m.put("Utilisateurs virtuels", s.getVirtualUsers());
        m.put("Montée en charge (s)", s.getRampUpSeconds());
        m.put("Durée (s)", s.getDurationSeconds());
        m.put("Itérations", s.getIterations());
        m.put("Temps de réflexion (ms)", s.getThinkTimeMs());
        m.put("Débit cible (req/s)", s.getTargetRps());
        m.put("Taux d’erreurs maximal (%)", s.getMaxErrorRatePercent());
        m.put("P95 maximal (ms)", s.getMaxP95Ms());
        m.put("Statut", s.getStatus() == null ? null : switch (s.getStatus().name()) {
            case "ACTIVE" -> "Actif";
            case "INACTIVE" -> "Inactif";
            case "DRAFT" -> "Brouillon";
            default -> s.getStatus().name();
        });
        return m;
    }

    public static Map<String, Object> of(Step s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Nom de l’étape", s.getName());
        m.put("Méthode", s.getMethod() != null ? s.getMethod().name() : null);
        m.put("URL de l’étape", s.getUrl());
        m.put("Statut HTTP attendu", s.getExpectedStatus());
        m.put("Délai maximal (s)", s.getTimeoutSeconds());
        m.put("Temps de réflexion de l’étape (ms)", s.getThinkTimeMs());
        return m;
    }

    public static Map<String, Object> of(ScheduledExecution s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Nom", s.getName());
        m.put("Scénario", s.getScenario() != null ? s.getScenario().getName() : null);
        m.put("Fréquence", s.getScheduleType() == null ? null : switch (s.getScheduleType().name()) {
            case "ONE_TIME" -> "Une seule fois";
            case "RECURRING" -> "Récurrente";
            default -> s.getScheduleType().name();
        });
        m.put("Expression CRON", s.getCronExpression());
        m.put("Date prévue", date(s.getRunAt()));
        m.put("Fuseau horaire", s.getTimezone());
        m.put("Fin", date(s.getEndAt()));
        return m;
    }

    private static String date(Instant instant) {
        return instant == null ? null : DATE_TIME.format(instant);
    }

    private static String shorten(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() > 120 ? t.substring(0, 119) + "…" : t;
    }
}
