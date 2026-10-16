package com.loadpilot.backend.service.report.export;

import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.service.audit.AuditSanitizer;
import com.loadpilot.backend.service.execution.TestRunMasking;
import com.loadpilot.backend.service.report.ExecutionStatistics;
import com.loadpilot.backend.service.report.ReportErrorEntry;
import com.loadpilot.backend.service.report.StepReportEntry;
import com.loadpilot.backend.service.report.export.ExportTable.Column;
import com.loadpilot.backend.service.report.pdf.ReportPdfWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Les quatre tableaux d'un rapport exporte (memes donnees que la page Rapport et le PDF, y compris
 * pour un rapport fige « Résumé seul ») : Resume, Etapes, Erreurs, Analyse dans le temps.
 *
 * Aucun secret : seules les donnees du rapport et de l'analyse dans le temps sont utilisees (jamais
 * en-tetes, corps, CSV, variables ni authentification de l'application) ; URL et messages passent
 * en plus par les filtres de masquage existants.
 */
public final class ReportTables {

    private ReportTables() {
    }

    /** Donnees du rapport ; timeline null si l'analyse dans le temps est indisponible. */
    public record Data(ExecutionReportResponse report, ExecutionTimelineResponse timeline, String statusReason,
                       Double cpuMaxPercent, Double memoryMaxMb, ZoneId zone) {
    }

    public static List<ExportTable> all(Data data) {
        return List.of(summary(data), steps(data), errors(data), timeline(data));
    }

    // ------------------------------------------------------------------ Resume

    public static ExportTable summary(Data data) {
        ExecutionReportResponse r = data.report();
        ExecutionStatistics s = r.statistics();
        ExecutionReportResponse.Thresholds th = r.thresholds();
        List<List<Object>> rows = new ArrayList<>();
        add(rows, "Scénario", r.scenarioName());
        add(rows, "Application", r.applicationName());
        add(rows, "Lancé le", r.startedAt());
        add(rows, "Terminé le", r.finishedAt());
        add(rows, "Durée (s)", r.duration() == null ? null : r.duration() / 1000.0);
        add(rows, "Dont pause (s)", r.pausedDurationMs() / 1000.0);
        add(rows, "Lancé par", r.triggeredByUsername() != null ? r.triggeredByUsername() : "Planification");
        add(rows, "Fuseau horaire des dates", data.zone().getId());
        add(rows, "Utilisateurs virtuels", r.virtualUsers());
        add(rows, "Ramp-up (s)", r.rampUpSeconds());
        add(rows, "Durée prévue (s)", r.durationSeconds() != null ? r.durationSeconds() : "Sans limite de temps");
        add(rows, "Itérations par utilisateur", r.iterations() != null ? r.iterations() : "Sans limite");
        add(rows, "Résultat", ReportPdfWriter.statusLabel(r.status()));
        add(rows, "Raison du résultat", data.statusReason() != null ? data.statusReason()
                : r.errorMessage() != null ? AuditSanitizer.clean(r.errorMessage()) : null);
        if (th != null) {
            add(rows, "Seuil : taux d’erreurs maximal (%)", th.maxErrorRatePercent());
            add(rows, "Seuil : P95 maximal (ms)", th.maxP95Ms() == null || th.maxP95Ms() == 0 ? "Aucun seuil" : th.maxP95Ms());
            add(rows, "Seuil taux d’erreurs respecté", th.errorRateOk() ? "Oui" : "Non");
            add(rows, "Seuil P95 respecté", th.p95Ok() == null ? "Sans objet" : th.p95Ok() ? "Oui" : "Non");
        }
        if (s != null) {
            add(rows, "Requêtes", s.totalRequests());
            add(rows, "Requêtes réussies", s.successfulRequests());
            add(rows, "Requêtes en échec", s.failedRequests());
            add(rows, "Taux d’erreurs (%)", s.errorRate());
            add(rows, "Débit moyen (req/s)", s.throughput());
            add(rows, "Temps min (ms)", s.minResponseTime());
            add(rows, "Temps moyen (ms)", s.avgResponseTime());
            add(rows, "Temps max (ms)", s.maxResponseTime());
            add(rows, "P50 (ms)", s.p50());
            add(rows, "P90 (ms)", s.p90());
            add(rows, "P95 (ms)", s.p95());
            add(rows, "P99 (ms)", s.p99());
        }
        add(rows, "CPU max de l’application (% d’un cœur)", data.cpuMaxPercent() != null ? data.cpuMaxPercent() : "Non mesuré");
        add(rows, "RAM max de l’application (Mo)", data.memoryMaxMb() != null ? data.memoryMaxMb() : "Non mesuré");
        if (r.retention() != null && r.retention().fromSnapshot()) {
            add(rows, "Rapport figé (détail des requêtes supprimé le)", r.retention().requestDetailsPurgedAt() != null
                    ? r.retention().requestDetailsPurgedAt() : r.retention().resultsPurgedAt());
        }
        add(rows, "Identifiant de l’exécution", r.id());
        return new ExportTable("Résumé", "Resume", List.of(Column.text("Indicateur"), Column.mixed("Valeur", 2)), rows);
    }

    private static void add(List<List<Object>> rows, String label, Object value) {
        rows.add(Arrays.asList(label, value));
    }

    // ------------------------------------------------------------------ Etapes

    public static ExportTable steps(Data data) {
        List<List<Object>> rows = new ArrayList<>();
        for (StepReportEntry s : data.report().steps() == null ? List.<StepReportEntry>of() : data.report().steps()) {
            rows.add(Arrays.asList(s.stepName(), s.method() == null ? null : s.method().name(), safeUrl(s.url()),
                    s.total(), s.success(), s.failed(), s.errorRate(), s.minResponseTime(), s.avgResponseTime(),
                    s.p95(), s.p99(), s.maxResponseTime()));
        }
        return new ExportTable("Étapes", "Etapes", List.of(
                Column.text("Étape"), Column.text("Méthode"), Column.text("URL"),
                Column.integer("Requêtes"), Column.integer("Succès"), Column.integer("Échecs"), Column.decimal("Taux d’erreurs (%)", 2),
                Column.integer("Min (ms)"), Column.integer("Moyenne (ms)"), Column.integer("P95 (ms)"), Column.integer("P99 (ms)"),
                Column.integer("Max (ms)")), rows);
    }

    // ------------------------------------------------------------------ Erreurs

    /** Erreurs regroupees par code HTTP, etape et message : nombre et premiere apparition (plus frequentes d'abord). */
    public static ExportTable errors(Data data) {
        Map<List<Object>, Object[]> groups = new LinkedHashMap<>();
        for (ReportErrorEntry e : data.report().errors() == null ? List.<ReportErrorEntry>of() : data.report().errors()) {
            String message = e.error() == null || e.error().isBlank() ? "Réponse inattendue" : AuditSanitizer.clean(e.error());
            List<Object> key = Arrays.asList(e.httpStatus(), e.stepName(), message);
            Object[] g = groups.computeIfAbsent(key, k -> new Object[] {0L, null});
            g[0] = (Long) g[0] + 1;
            if (e.timestamp() != null && (g[1] == null || e.timestamp().isBefore((Instant) g[1]))) g[1] = e.timestamp();
        }
        List<List<Object>> rows = new ArrayList<>();
        groups.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<List<Object>, Object[]> en) -> (Long) en.getValue()[0]).reversed()
                        .thenComparing(en -> en.getValue()[1] == null ? Instant.MAX : (Instant) en.getValue()[1]))
                .forEach(en -> rows.add(Arrays.asList(en.getKey().get(0), en.getKey().get(1), en.getKey().get(2),
                        en.getValue()[0], en.getValue()[1])));
        return new ExportTable("Erreurs", "Erreurs", List.of(
                Column.integer("Code HTTP"), Column.text("Étape"), Column.text("Message"), Column.integer("Nombre"),
                Column.dateTime("Première apparition")), rows);
    }

    // ------------------------------------------------------------------ Analyse dans le temps

    public static ExportTable timeline(Data data) {
        ExecutionTimelineResponse tl = data.timeline();
        List<List<Object>> rows = new ArrayList<>();
        if (tl != null && tl.t() != null) {
            ExecutionTimelineResponse.ResourceSeries res = tl.resources();
            for (int i = 0; i < tl.t().size(); i++) {
                int second = tl.t().get(i);
                rows.add(Arrays.asList(second, Instant.ofEpochMilli(tl.startEpochMs() + second * 1000L),
                        tl.virtualUsersAvailable() ? at(tl.activeVirtualUsers(), i) : null,
                        at(tl.requests(), i), at(tl.throughput(), i), at(tl.avgMs(), i), at(tl.p95Ms(), i), at(tl.errors(), i),
                        res == null ? null : at(res.cpuPercent(), i), res == null ? null : at(res.memoryMb(), i)));
            }
        }
        return new ExportTable("Analyse dans le temps", "Analyse-dans-le-temps", List.of(
                Column.integer("Temps depuis le début (s)"), Column.dateTime("Heure"), Column.integer("VU actifs"),
                Column.integer("Requêtes"), Column.decimal("Débit (req/s)", 1), Column.integer("Temps moyen (ms)"),
                Column.integer("P95 (ms)"), Column.integer("Erreurs"), Column.decimal("CPU (% d’un cœur)", 1),
                Column.decimal("RAM (Mo)", 1)), rows);
    }

    private static Object at(List<?> values, int i) {
        return values == null || i >= values.size() ? null : values.get(i);
    }

    /** URL d'une etape sans parametre sensible (cle, jeton, mot de passe...) ni valeur secrete. */
    static String safeUrl(String url) {
        return url == null ? null : AuditSanitizer.clean(TestRunMasking.maskUrl(url, Set.of(), List.of()));
    }

    /** Valeur numerique d'une cellule (BigDecimal, Integer, Long, Double), null sinon. */
    static Double number(Object value) {
        if (value instanceof BigDecimal b) return b.doubleValue();
        if (value instanceof Number n) return n.doubleValue();
        return null;
    }

    static boolean isNumber(Object value) {
        return value instanceof Number;
    }

    static boolean present(Object value) {
        return Objects.nonNull(value) && !(value instanceof String s && s.isEmpty());
    }
}
