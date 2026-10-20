package com.loadpilot.backend.service.report.export;

import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.ExecutionService;
import com.loadpilot.backend.service.execution.ExecutionStatusReason;
import com.loadpilot.backend.service.impl.ExecutionTimelineService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exports d'un rapport d'execution TERMINEE (aussi pour un rapport fige « Résumé seul ») :
 *  - Excel (.xlsx) : feuilles Resume, Etapes, Erreurs, Analyse dans le temps ;
 *  - CSV : UN tableau par fichier (Etapes par defaut), ou les quatre dans une archive .zip.
 * 409 pour une execution en attente, en cours ou en pause.
 */
@Service
@RequiredArgsConstructor
public class ReportExportService {

    private static final Logger log = LoggerFactory.getLogger(ReportExportService.class);

    /** Tableaux CSV disponibles (parametre « table »). */
    public static final Map<String, String> CSV_TABLES = Map.of(
            "steps", "étapes", "errors", "erreurs", "timeline", "analyse dans le temps", "summary", "résumé");

    private final ExecutionRepository executionRepository;
    private final ExecutionService executionService;
    private final ExecutionTimelineService timelineService;
    private final com.loadpilot.backend.repository.ExecutionStepResultRepository resultRepository;
    private final com.loadpilot.backend.service.impl.ResultLoadGuard resultLoadGuard;
    private Clock clock = Clock.systemDefaultZone();

    public record ExportFile(String fileName, String contentType, byte[] content, String label) {
    }

    /**
     * Memoire de l'export entier reservee (rapport, analyse dans le temps et fichier, gardes jusqu'a
     * la fin de la transaction) : couvre aussi le rapport et l'analyse appeles a l'interieur.
     */
    private com.loadpilot.backend.service.impl.ResultLoadGuard.Reservation reserve(UUID executionId) {
        return resultLoadGuard.reserve(com.loadpilot.backend.service.impl.ResultLoadGuard.Load.EXPORT,
                resultRepository.countAllByExecutionId(executionId));
    }

    @Transactional(readOnly = true)
    public ExportFile xlsx(UUID executionId) {
        try (var reservation = reserve(executionId)) {
            ReportTables.Data data = data(executionId);
            ExecutionReportResponse r = data.report();
            byte[] bytes = XlsxWriter.write(ReportTables.all(data), "Rapport — " + r.scenarioName(), data.zone(), Instant.now(clock));
            return new ExportFile(ReportFileNames.of(r.scenarioName(), r.startedAt(), data.zone(), null, "xlsx"),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes, "Excel");
        }
    }

    @Transactional(readOnly = true)
    public ExportFile csv(UUID executionId, String table) {
        String key = table == null || table.isBlank() ? "steps" : table;
        if (!CSV_TABLES.containsKey(key)) {
            throw new BadRequestException("Tableau inconnu : « " + table + " » (attendu : steps, errors, timeline ou summary).");
        }
        try (var reservation = reserve(executionId)) {
            ReportTables.Data data = data(executionId);
            ExportTable t = switch (key) {
                case "errors" -> ReportTables.errors(data);
                case "timeline" -> ReportTables.timeline(data);
                case "summary" -> ReportTables.summary(data);
                default -> ReportTables.steps(data);
            };
            ExecutionReportResponse r = data.report();
            return new ExportFile(ReportFileNames.of(r.scenarioName(), r.startedAt(), data.zone(), t.fileSuffix(), "csv"),
                    "text/csv; charset=UTF-8", CsvTableWriter.write(t, data.zone()), "CSV (" + CSV_TABLES.get(key) + ")");
        }
    }

    /** Les quatre tableaux en CSV separes, dans une archive .zip. */
    @Transactional(readOnly = true)
    public ExportFile csvZip(UUID executionId) {
        try (var reservation = reserve(executionId)) {
            return csvZip(data(executionId));
        }
    }

    private ExportFile csvZip(ReportTables.Data data) {
        ExecutionReportResponse r = data.report();
        java.io.ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, java.nio.charset.StandardCharsets.UTF_8)) {
            for (ExportTable t : ReportTables.all(data)) {
                zip.putNextEntry(new ZipEntry(ReportFileNames.of(r.scenarioName(), r.startedAt(), data.zone(), t.fileSuffix(), "csv")));
                zip.write(CsvTableWriter.write(t, data.zone()));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Archive impossible", e);
        }
        return new ExportFile(ReportFileNames.of(r.scenarioName(), r.startedAt(), data.zone(), "CSV", "zip"), "application/zip",
                out.toByteArray(), "CSV (archive .zip des 4 tableaux)");
    }

    private ReportTables.Data data(UUID executionId) {
        Execution execution = executionRepository.findByIdWithScenarioAndApplication(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution introuvable : " + executionId));
        if (execution.getStatus().isActive()) {
            throw new ConflictException("Le rapport est exportable une fois l’exécution terminée.");
        }
        ExecutionReportResponse report = executionService.getReport(executionId);
        ExecutionTimelineResponse timeline = null;
        try {
            timeline = timelineService.timeline(executionId);
        } catch (RuntimeException e) {
            log.warn("Analyse dans le temps indisponible pour l'export de l'execution {} : {}", executionId, e.getMessage());
        }
        Double cpu = execution.getCpuMaxPercent() != null ? execution.getCpuMaxPercent() : maxOf(timeline, true);
        Double ram = execution.getMemoryMaxMb() != null ? execution.getMemoryMaxMb() : maxOf(timeline, false);
        return new ReportTables.Data(report, timeline, ExecutionStatusReason.of(execution), cpu, ram, clock.getZone());
    }

    private static Double maxOf(ExecutionTimelineResponse tl, boolean cpu) {
        if (tl == null || tl.resources() == null) return null;
        List<Double> values = cpu ? tl.resources().cpuPercent() : tl.resources().memoryMb();
        return values == null ? null : values.stream().filter(Objects::nonNull).max(Double::compare).orElse(null);
    }

    /** Tests : horloge et fuseau fixes. */
    void setClock(Clock clock) {
        this.clock = clock;
    }
}
