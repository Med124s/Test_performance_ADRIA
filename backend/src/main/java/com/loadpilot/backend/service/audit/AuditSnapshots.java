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
        // Proxy : jamais le mot de passe.
        com.loadpilot.backend.enums.ProxyMode proxy = a.getProxyMode() == null ? com.loadpilot.backend.enums.ProxyMode.INHERIT : a.getProxyMode();
        m.put("Proxy", switch (proxy) {
            case INHERIT -> "Proxy du serveur";
            case NONE -> "Aucun proxy";
            case CUSTOM -> "Proxy de l’application";
        });
        m.put("Hôte du proxy", a.getProxyHost());
        m.put("Port du proxy", a.getProxyPort());
        m.put("Identifiant du proxy", a.getProxyUsername());
        m.put("Supervision sans proxy", Boolean.TRUE.equals(a.getMonitoringWithoutProxy()) ? "Oui" : null);
        // Autorites TLS : leurs sujets (le PEM complet n'apporte rien au journal).
        java.util.List<com.loadpilot.backend.service.http.TlsTrust.CertificateInfo> cas =
                com.loadpilot.backend.service.http.TlsTrust.describe(a.getTlsCaCertificate());
        m.put("Autorités de certification TLS", cas.isEmpty() ? null
                : String.join(" ; ", cas.stream().map(com.loadpilot.backend.service.http.TlsTrust.CertificateInfo::subject).toList()));
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
        m.put("Variation du temps de réflexion (± %)", s.getThinkTimeVariationPercent());
        m.put("Débit cible (req/s)", s.getTargetRps());
        m.put("Taux d’erreurs maximal (%)", s.getMaxErrorRatePercent());
        m.put("P95 maximal (ms)", s.getMaxP95Ms());
        m.put("Frein automatique : taux d’erreurs (%)", s.getErrorBrakeRatePercent());
        m.put("Frein automatique : fenêtre (s)", s.getErrorBrakeRatePercent() == null ? null : s.getErrorBrakeWindowSeconds());
        boolean throttleOn = !Boolean.FALSE.equals(s.getThrottleOn429());
        m.put("Ralentissement sur 429", throttleOn ? "Actif" : "Désactivé");
        m.put("Attente après un 429 sans Retry-After (s)", throttleOn ? s.getThrottleDefaultDelaySeconds() : null);
        m.put("Rejeux après un 429", throttleOn ? s.getThrottleMaxRetries() : null);
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
        m.put("Temps de réponse maximal (ms)", s.getMaxResponseTimeMs());
        // Champ et comparaison seulement : la valeur attendue peut provenir d'une donnee de test.
        m.put("Assertion JSON", s.getAssertionJsonPath() == null || s.getAssertionJsonPath().isBlank() ? null
                : s.getAssertionJsonPath() + (s.getAssertionJsonOperator() == com.loadpilot.backend.enums.JsonAssertionOperator.CONTAINS
                        ? " contient…" : " égale…"));
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
