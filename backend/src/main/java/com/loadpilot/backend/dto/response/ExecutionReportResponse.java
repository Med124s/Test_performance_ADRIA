package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.service.report.ExecutionStatistics;
import com.loadpilot.backend.service.report.ReportErrorEntry;
import com.loadpilot.backend.service.report.StepReportEntry;
import java.time.Instant;
import java.util.List;

/**
 * P1-A — GET /api/executions/{id}/report (voir prompt section 16).
 *
 * Volontairement DISTINCT de ExecutionDetailResponse : celui-ci reste la
 * vue "brute" (liste plate des resultats, deja utilisee par
 * ExecutionDetail.tsx) ; celui-la est une vue AGREGEE/statistique dediee au
 * reporting (ExecutionReport.tsx) - calculer percentiles/agregation par
 * step a chaque appel de GET /api/executions/{id} (deja appele frequemment
 * par le polling du statut, voir P0-A) serait un cout inutile pour un
 * usage qui n'en a pas besoin la plupart du temps.
 */
public record ExecutionReportResponse(
        String id,
        String scenarioId,
        String scenarioName,
        String applicationId,
        String applicationName,
        ExecutionStatus status,
        Instant startedAt,
        Instant finishedAt,
        Long duration,
        String errorMessage,
        // ABSENCE DOCUMENTEE : "thinkTimeMs" n'existe PAS ici. Contrairement a
        // virtualUsers/rampUpSeconds/durationSeconds/iterations, Execution
        // (voir P0-A) n'a jamais fige de colonne think_time_ms au lancement -
        // seul Scenario.thinkTimeMs existe, et il reflete la configuration
        // COURANTE du Scenario, pas forcement celle utilisee par CETTE
        // execution passee si le Scenario a ete modifie depuis (violerait le
        // meme principe d'immutabilite que P0-B a etabli pour les autres
        // parametres). Plutot que d'afficher une valeur potentiellement
        // fausse, ce champ est absent - lacune reelle de P0-A, documentee
        // ici, non corrigee dans cette phase (hors perimetre de P1-A).
        Integer virtualUsers,
        Integer rampUpSeconds,
        Integer durationSeconds,
        Integer iterations,
        /** P1-C — voir ExecutionHistoryResponse.triggeredByUsername : meme
         * champ, meme absence pour toute Execution anterieure a P1-B. */
        String triggeredByUsername,
        ExecutionStatistics statistics,
        List<StepReportEntry> steps,
        List<ReportErrorEntry> errors
) {
}
