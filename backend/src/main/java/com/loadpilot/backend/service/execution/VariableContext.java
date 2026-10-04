package com.loadpilot.backend.service.execution;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contexte de variables d'UN utilisateur virtuel (jamais partage entre threads).
 *
 * Resolution d'un ${nom}, la premiere source trouvee l'emporte :
 *   1. capture   : valeur extraite d'une reponse d'une etape precedente DE L'ITERATION EN COURS
 *                  (videe au debut de chaque iteration : une capture de l'iteration N n'est
 *                  jamais reutilisee par erreur a l'iteration N+1) ;
 *   2. CSV       : ligne attribuee a ce VU (ou a cette iteration, selon la distribution) ;
 *   3. scenario  : variables constantes du scenario ;
 *   4. authToken : jeton de la connexion automatique de l'application (variable integree).
 */
public final class VariableContext {

    public enum Source { CAPTURE, CSV, SCENARIO, AUTOMATIC }

    private final Map<String, String> captures = new HashMap<>();
    private final Map<String, String> scenarioVariables;
    private final String authToken;
    private Map<String, String> csvRow = Map.of();

    public VariableContext(Map<String, String> scenarioVariables, String authToken) {
        this.scenarioVariables = scenarioVariables == null ? Map.of() : Map.copyOf(scenarioVariables);
        this.authToken = authToken;
    }

    /** Debut d'iteration : captures videes, ligne CSV de cette iteration. */
    public void startIteration(Map<String, String> csvRow) {
        captures.clear();
        this.csvRow = csvRow == null ? Map.of() : csvRow;
    }

    public void capture(String name, String value) {
        captures.put(name, value);
    }

    /** Valeur resolue, ou null si la variable est introuvable dans toutes les sources. */
    public String get(String name) {
        String value = captures.get(name);
        if (value != null) return value;
        value = csvRow.get(name);
        if (value != null) return value;
        value = scenarioVariables.get(name);
        if (value != null) return value;
        return ResolvedAuth.TOKEN_VARIABLE.equals(name) ? authToken : null;
    }

    public Source sourceOf(String name) {
        if (captures.containsKey(name)) return Source.CAPTURE;
        if (csvRow.containsKey(name)) return Source.CSV;
        if (scenarioVariables.containsKey(name)) return Source.SCENARIO;
        return ResolvedAuth.TOKEN_VARIABLE.equals(name) && authToken != null ? Source.AUTOMATIC : null;
    }

    /** Toutes les variables visibles (priorite appliquee), pour l'affichage du test detaille. */
    public Map<String, String> snapshot() {
        Map<String, String> all = new LinkedHashMap<>();
        if (authToken != null) all.put(ResolvedAuth.TOKEN_VARIABLE, authToken);
        all.putAll(scenarioVariables);
        all.putAll(csvRow);
        all.putAll(captures);
        return all;
    }
}
