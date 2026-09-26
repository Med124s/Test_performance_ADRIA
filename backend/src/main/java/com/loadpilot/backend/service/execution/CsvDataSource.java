package com.loadpilot.backend.service.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-Q Etape B — parse REELLEMENT un CSV data source (premiere ligne = noms
 * de colonnes/variables) en lignes exploitables par le moteur (voir
 * Scenario.csvData, HttpClientExecutionEngine). Format volontairement
 * minimal (separateur virgule, pas d'echappement de guillemets/virgules
 * internes) — suffisant pour le cas d'usage reel vise (identifiants de test,
 * ex. "username,password") sans introduire un vrai parseur RFC 4180 complet
 * pour un besoin non demontre.
 *
 * JAMAIS de mot de passe/valeur sensible loggue : en cas de format invalide,
 * seul le nombre de colonnes attendu/trouve est journalise, jamais le
 * contenu de la ligne.
 */
public final class CsvDataSource {

    private CsvDataSource() {
    }

    /**
     * Renvoie une ligne (Map colonne -> valeur) par ligne de donnees reelle
     * du CSV. Une ligne CSV avec moins de colonnes que l'en-tete voit ses
     * colonnes manquantes absentes du Map (jamais une valeur inventee) ;
     * avec plus de colonnes, les colonnes en trop sont ignorees. Un CSV vide/
     * null/sans ligne de donnees renvoie une liste vide (aucune variable
     * disponible — jamais une erreur, comportement historique inchange pour
     * un Scenario sans CSV).
     */
    public static List<Map<String, String>> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) {
            // En-tete seul (ou rien d'exploitable) : aucune ligne de donnees.
            return List.of();
        }
        String[] columns = splitLine(lines[0]);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] values = splitLine(lines[i]);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < columns.length && c < values.length; c++) {
                row.put(columns[c].trim(), values[c].trim());
            }
            rows.add(row);
        }
        return rows;
    }

    private static String[] splitLine(String line) {
        return line.split(",", -1);
    }
}
