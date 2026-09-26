package com.loadpilot.backend.service.execution;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resultat de la preparation transactionnelle courte (voir
 * ExecutionTransactionHelper.prepareAndStart) - donne a l'engine tout ce
 * dont il a besoin sans jamais lui passer d'entite JPA geree.
 * "loadSpec" est la copie figee (deja persistee sur l'Execution) des
 * parametres de charge du Scenario au moment du lancement.
 *
 * P1-B : "triggeredByAppUserId"/"scheduleId" sont de simples UUID (jamais
 * une entite JPA geree transportee hors de sa transaction d'origine - voir
 * ExecutionService.execute) - utilises par runAsync UNIQUEMENT pour
 * construire la Notification emise une fois l'Execution terminee
 * (NotificationServiceImpl.create resout une reference fraiche dans SA
 * PROPRE transaction), jamais pour une nouvelle logique de securite. */
public record PreparedExecution(
        UUID executionId,
        List<StepExecutionSpec> steps,
        String applicationBaseUrl,
        LoadTestSpec loadSpec,
        /** P1-Q Etape B - une ligne de variables par VU, deja parsee (voir
         * CsvDataSource) - liste vide = aucune donnee CSV sur ce Scenario. */
        List<Map<String, String>> vuVariableRows,
        String scenarioName,
        UUID triggeredByAppUserId,
        UUID scheduleId
) {
}
