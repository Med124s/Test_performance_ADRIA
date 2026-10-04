package com.loadpilot.backend.service.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Jeu de donnees CSV d'un scenario (Scenario.csvData) : premiere ligne = noms de colonnes,
 * utilisables comme variables ${colonne}, puis une ligne par utilisateur virtuel
 * (reutilisation cyclique, voir HttpClientExecutionEngine).
 *
 * Format (memes regles que le frontend, voir csvModel.ts) : BOM UTF-8 ignore, separateur
 * "," ou ";" detecte sur la ligne d'en-tete, guillemets RFC 4180 ("" pour un guillemet,
 * separateur ou retour a la ligne autorises entre guillemets), lignes vides ignorees,
 * valeurs hors guillemets sans espaces de bord.
 *
 * JAMAIS de valeur journalisee (le CSV peut contenir des identifiants de test).
 */
public final class CsvDataSource {

    public static final int MAX_LENGTH = 1_000_000;
    private static final Pattern COLUMN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private CsvDataSource() {
    }

    /** Enregistrement CSV et numero (1-base) de sa premiere ligne physique. */
    private record Record(List<String> values, int line) {
    }

    /** Resultat d'analyse : colonnes, lignes de donnees, ou premiere erreur rencontree. */
    public record Parsed(List<String> columns, List<List<String>> rows, char separator, String error) {
    }

    /**
     * Une ligne (Map colonne -> valeur) par ligne de donnees. Tolerant pour le moteur : une
     * ligne plus courte n'a pas les colonnes manquantes, une ligne plus longue voit ses
     * valeurs en trop ignorees. CSV vide ou invalide : aucune variable.
     */
    public static List<Map<String, String>> parse(String csv) {
        Parsed parsed = analyze(csv);
        if (parsed.columns().isEmpty()) return List.of();
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> values : parsed.rows()) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < parsed.columns().size() && c < values.size(); c++) {
                row.put(parsed.columns().get(c), values.get(c));
            }
            rows.add(row);
        }
        return rows;
    }

    /** Message d'erreur lisible, ou null si le CSV est vide ou valide. */
    public static String validate(String csv) {
        return analyze(csv).error();
    }

    public static Parsed analyze(String csv) {
        if (csv == null || csv.isBlank()) {
            return new Parsed(List.of(), List.of(), ',', null);
        }
        if (csv.length() > MAX_LENGTH) {
            return new Parsed(List.of(), List.of(), ',', "Les données CSV ne doivent pas dépasser 1 000 000 caractères.");
        }
        String text = csv.charAt(0) == '﻿' ? csv.substring(1) : csv;
        char separator = detectSeparator(text);
        List<Record> records = new ArrayList<>();
        String error = split(text, separator, records);
        if (error != null) return new Parsed(List.of(), List.of(), separator, error);
        if (records.isEmpty()) return new Parsed(List.of(), List.of(), separator, null);

        List<String> columns = records.get(0).values();
        Set<String> seen = new HashSet<>();
        for (String column : columns) {
            if (!COLUMN_NAME.matcher(column).matches()) {
                return new Parsed(List.of(), List.of(), separator, column.isEmpty()
                        ? "Ligne d'en-tête : une colonne n'a pas de nom."
                        : "Nom de colonne invalide « " + column + " » : lettres, chiffres et _ uniquement, sans commencer par un chiffre.");
            }
            if (!seen.add(column)) {
                return new Parsed(List.of(), List.of(), separator, "Colonne en double : « " + column + " ».");
            }
        }
        // Toutes les lignes sont conservees (parse() reste tolerant pour le moteur) ; seule la
        // premiere incoherence est retenue comme erreur de validation.
        List<List<String>> rows = new ArrayList<>();
        String mismatch = null;
        for (Record record : records.subList(1, records.size())) {
            if (mismatch == null && record.values().size() != columns.size()) {
                mismatch = "Ligne " + record.line() + " : " + record.values().size()
                        + " colonne(s) au lieu de " + columns.size() + ".";
            }
            rows.add(record.values());
        }
        if (mismatch == null && rows.isEmpty()) {
            mismatch = "Ajoutez au moins une ligne de données sous la ligne d'en-tête.";
        }
        return new Parsed(columns, rows, separator, mismatch);
    }

    /** ";" si la ligne d'en-tete en contient plus que de "," (hors guillemets), sinon ",". */
    static char detectSeparator(String text) {
        int commas = 0;
        int semicolons = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted && (c == '\n' || c == '\r')) break;
            else if (!quoted && c == ',') commas++;
            else if (!quoted && c == ';') semicolons++;
        }
        return semicolons > commas ? ';' : ',';
    }

    private static String split(String text, char separator, List<Record> records) {
        List<String> values = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        int line = 1;
        int recordLine = 1;
        int i = 0;
        while (i <= text.length()) {
            char c = i < text.length() ? text.charAt(i) : '\n';
            boolean end = i == text.length();
            if (quoted) {
                if (end) return "Guillemet non fermé (ligne " + recordLine + ").";
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') line++;
                    field.append(c);
                }
            } else if (c == '"' && field.toString().isBlank()) {
                quoted = true;
                wasQuoted = true;
                field.setLength(0);
            } else if (c == separator) {
                values.add(wasQuoted ? field.toString() : field.toString().strip());
                field.setLength(0);
                wasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                values.add(wasQuoted ? field.toString() : field.toString().strip());
                boolean blank = values.size() == 1 && values.get(0).isEmpty() && !wasQuoted;
                if (!blank) records.add(new Record(List.copyOf(values), recordLine));
                values.clear();
                field.setLength(0);
                wasQuoted = false;
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                line++;
                recordLine = line;
            } else if (wasQuoted) {
                // Texte apres un guillemet fermant : toleré uniquement s'il est blanc.
                if (!Character.isWhitespace(c)) {
                    return "Ligne " + recordLine + " : caractère inattendu après un guillemet fermant.";
                }
            } else {
                field.append(c);
            }
            i++;
        }
        return null;
    }
}
