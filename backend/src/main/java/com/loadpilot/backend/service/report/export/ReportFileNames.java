package com.loadpilot.backend.service.report.export;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Noms des fichiers exportes, lisibles et surs : « Rapport_Parcours-bancaire_2026-10-10_16h38.xlsx »
 * (scenario sans accent ni caractere special, espaces remplaces par des tirets, date et heure de
 * lancement a l'heure locale), suivi au besoin du tableau (« _Etapes.csv »).
 */
public final class ReportFileNames {

    private ReportFileNames() {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH'h'mm");

    public static String of(String scenarioName, Instant startedAt, ZoneId zone, String suffix, String extension) {
        String base = "Rapport_" + slug(scenarioName) + (startedAt == null ? "" : "_" + WHEN.format(startedAt.atZone(zone)));
        return base + (suffix == null || suffix.isBlank() ? "" : "_" + suffix) + "." + extension;
    }

    static String slug(String name) {
        String ascii = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('œ', 'o').replace('Œ', 'O').replace('æ', 'a').replace('Æ', 'A');
        String slug = ascii.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 60) slug = slug.substring(0, 60).replaceAll("-+$", "");
        return slug.isEmpty() ? "Execution" : slug;
    }
}
