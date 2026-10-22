package com.loadpilot.backend.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Durees de conservation de test, pour verifier les purges en local sans attendre (variables
 * LOADPILOT_*_RETENTION_OVERRIDE, ex. 10m, 2h, 3d). Lues UNIQUEMENT par les jobs de purge
 * (AuditRetentionJob, ResultsRetentionJob) : Parametres > Systeme, sa validation et les durees
 * affichees restent inchangees. Vide = duree reglee dans Parametres. Refusees en profil "prod"
 * (demarrage annule) : jamais de conservation raccourcie en production.
 */
@Component
public class RetentionOverrides {

    private static final Logger log = LoggerFactory.getLogger(RetentionOverrides.class);

    /** Une duree de conservation : la surcharge de test si elle existe, sinon les jours de Parametres. */
    public record Retention(Duration duration, String label) {
    }

    private final Duration audit;
    private final Duration results;
    private final Duration requestDetails;

    public RetentionOverrides(@Value("${app.audit.retention-override:}") String audit,
                              @Value("${app.results.retention-override:}") String results,
                              @Value("${app.results.request-details-retention-override:}") String requestDetails,
                              Environment environment) {
        this.audit = parse("LOADPILOT_AUDIT_RETENTION_OVERRIDE", audit);
        this.results = parse("LOADPILOT_RESULTS_RETENTION_OVERRIDE", results);
        this.requestDetails = parse("LOADPILOT_REQUEST_DETAILS_RETENTION_OVERRIDE", requestDetails);
        boolean any = this.audit != null || this.results != null || this.requestDetails != null;
        if (any && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("LOADPILOT_*_RETENTION_OVERRIDE est reserve aux tests locaux : "
                    + "a retirer de l'environnement en profil prod (les durees se reglent dans Parametres > Systeme).");
        }
        warn("LOADPILOT_AUDIT_RETENTION_OVERRIDE", "journal d'audit", this.audit);
        warn("LOADPILOT_RESULTS_RETENTION_OVERRIDE", "resultats detailles", this.results);
        warn("LOADPILOT_REQUEST_DETAILS_RETENTION_OVERRIDE", "en-tetes et corps des requetes", this.requestDetails);
    }

    public Retention audit(int days) {
        return resolve(audit, "LOADPILOT_AUDIT_RETENTION_OVERRIDE", days);
    }

    public Retention results(int days) {
        return resolve(results, "LOADPILOT_RESULTS_RETENTION_OVERRIDE", days);
    }

    public Retention requestDetails(int days) {
        return resolve(requestDetails, "LOADPILOT_REQUEST_DETAILS_RETENTION_OVERRIDE", days);
    }

    private static Retention resolve(Duration override, String variable, int days) {
        if (override == null) return new Retention(Duration.ofDays(days), days + " jours");
        return new Retention(override, label(override) + " (durée de test " + variable + ")");
    }

    /** null si vide ; unite obligatoire (« 10 » serait lu en millisecondes par Spring). */
    static Duration parse(String variable, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        Duration duration;
        try {
            if (value.matches("\\d+")) throw new IllegalArgumentException("unite manquante");
            duration = DurationStyle.detectAndParse(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(variable + " invalide ('" + value + "') : attendu une duree avec son unite, ex. 10m, 2h, 3d.");
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalStateException(variable + " invalide ('" + value + "') : la duree doit etre positive.");
        }
        return duration;
    }

    /** « 10 minutes », « 2 heures », « 3 jours », « 90 secondes ». */
    static String label(Duration d) {
        long s = d.toSeconds();
        if (s % 86_400 == 0) return plural(s / 86_400, "jour");
        if (s % 3_600 == 0) return plural(s / 3_600, "heure");
        if (s % 60 == 0) return plural(s / 60, "minute");
        return plural(s, "seconde");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n > 1 ? "s" : "");
    }

    private static void warn(String variable, String what, Duration override) {
        if (override != null) {
            log.warn("{} actif : {} purges au-dela de {} (duree de Parametres ignoree par la purge, tests locaux uniquement).",
                    variable, what, label(override));
        }
    }
}
