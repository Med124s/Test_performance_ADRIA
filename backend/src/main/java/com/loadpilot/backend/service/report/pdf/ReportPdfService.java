package com.loadpilot.backend.service.report.pdf;

import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.ExecutionService;
import com.loadpilot.backend.service.execution.ExecutionStatusReason;
import com.loadpilot.backend.service.impl.ExecutionTimelineService;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rapport PDF d'une execution terminee (Reussie, Seuils depasses, Echouee, Annulee) : memes donnees
 * que la page Rapport (rapport statistique et analyse dans le temps), y compris pour un rapport fige
 * dont le detail des requetes a ete purge. 409 pour une execution en attente, en cours ou en pause.
 */
@Service
@RequiredArgsConstructor
public class ReportPdfService {

    private static final Logger log = LoggerFactory.getLogger(ReportPdfService.class);

    private final ExecutionRepository executionRepository;
    private final ExecutionService executionService;
    private final ExecutionTimelineService timelineService;
    private Clock clock = Clock.systemDefaultZone();

    public record PdfFile(String fileName, byte[] content) {
    }

    @Transactional(readOnly = true)
    public PdfFile export(UUID executionId) {
        Execution execution = executionRepository.findByIdWithScenarioAndApplication(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));
        if (execution.getStatus().isActive()) {
            throw new ConflictException("Le rapport PDF est disponible une fois l’exécution terminée.");
        }
        ExecutionReportResponse report = executionService.getReport(executionId);
        ExecutionTimelineResponse timeline = null;
        try {
            timeline = timelineService.timeline(executionId);
        } catch (RuntimeException e) {
            // Le PDF reste utile sans les graphiques (section « indisponible »).
            log.warn("Analyse dans le temps indisponible pour le PDF de l'execution {} : {}", executionId, e.getMessage());
        }
        ZoneId zone = clock.getZone();
        byte[] pdf = new ReportPdfWriter().write(new ReportPdfWriter.Data(report, timeline, ExecutionStatusReason.of(execution), zone, Instant.now(clock)));
        return new PdfFile(fileName(report, zone), pdf);
    }

    /** « Rapport_Parcours-bancaire_2026-10-10_18h13.pdf » (meme convention que les exports Excel et CSV). */
    static String fileName(ExecutionReportResponse report, ZoneId zone) {
        return com.loadpilot.backend.service.report.export.ReportFileNames.of(report.scenarioName(), report.startedAt(), zone, null, "pdf");
    }

    /** Tests : horloge et fuseau fixes. */
    void setClock(Clock clock) {
        this.clock = clock;
    }
}
