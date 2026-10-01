package com.loadpilot.backend.service.execution;

import java.util.List;
import java.util.Map;

/**
 * Abstraction du moteur d'execution reel d'un Scenario - point d'extension
 * unique et testable. Une seule implementation reelle (voir
 * HttpClientExecutionEngine, java.net.http.HttpClient + threads virtuels
 * JDK 21) - voir rapport P0-A pour la comparaison argumentee face a
 * JMeter/Gatling qui a mene a ce choix.
 *
 * Evolution P0 (charge reelle) : {@code loadSpec} pilote reellement le
 * nombre d'utilisateurs virtuels concurrents, le ramp-up, la duree/le
 * nombre d'iterations et le think time - {@code handle} permet a
 * l'appelant (ExecutionServiceImpl) d'annuler REELLEMENT l'execution en
 * cours (interruption des threads, pas un simple changement de statut).
 */
public interface ExecutionEngine {

    /**
     * Execute reellement, avec la charge decrite par {@code loadSpec}, les
     * Steps fournis contre {@code applicationBaseUrl}. Ne leve jamais :
     * tout echec (HTTP, reseau, timeout, annulation) est capture dans le
     * resultat retourne. {@code handle.isCancelled()} est verifie de
     * maniere cooperative avant chaque etape/iteration de chaque
     * utilisateur virtuel.
     *
     * {@code vuVariableRows} (P1-Q Etape B) — une ligne de variables
     * ({@code ${nomColonne}}, voir CsvDataSource/VariableResolver) par
     * utilisateur virtuel, distribuee de maniere CYCLIQUE si elle contient
     * moins de lignes que d'utilisateurs virtuels demandes (ex. 2 lignes
     * pour 5 VUs -> VU0/VU2/VU4 utilisent la ligne 0, VU1/VU3 la ligne 1) -
     * jamais un echec, jamais une ligne vide inventee. Liste vide = aucune
     * variable de donnees (comportement historique inchange).
     */
    ScenarioExecutionOutcome execute(List<StepExecutionSpec> steps, String applicationBaseUrl,
            LoadTestSpec loadSpec, RunningExecutionHandle handle, List<Map<String, String>> vuVariableRows);

    /**
     * Passage produit reel (2026-09-30) — envoie UNE VRAIE requete par
     * etape fournie (en parallele), jamais une Execution/charge repetee.
     * Voir HttpClientExecutionEngine.testSteps pour l'implementation reelle
     * et StepController POST /api/steps/test-batch pour l'usage.
     */
    List<StepOutcome> testSteps(List<StepExecutionSpec> steps, String applicationBaseUrl);
}
