package com.loadpilot.backend.service.report.pdf;

import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.service.report.ExecutionStatistics;
import com.loadpilot.backend.service.report.StepReportEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Conclusion automatique du rapport : quelques phrases courtes qui reprennent les points cles
 * (resultat et seuils, charge, etape la plus lente, degradation, erreur principale, ressources).
 * Uniquement des faits mesures, jamais une recommandation inventee.
 */
public final class ReportConclusion {

    private ReportConclusion() {
    }

    public static List<String> lines(ExecutionReportResponse r, ExecutionTimelineResponse tl) {
        List<String> out = new ArrayList<>();
        ExecutionStatistics s = r.statistics();
        ExecutionReportResponse.Thresholds th = r.thresholds();

        out.add(resultLine(r, th));
        if (s != null && s.totalRequests() > 0) {
            out.add(PdfText.integer(s.totalRequests()) + " requêtes en " + PdfText.duration(r.duration()) + " avec "
                    + (r.virtualUsers() == null ? "?" : PdfText.integer(r.virtualUsers())) + " utilisateur(s) virtuel(s) : débit moyen "
                    + PdfText.rps(s.throughput() == null ? null : s.throughput().doubleValue()) + ", P95 " + PdfText.ms(s.p95())
                    + ", P99 " + PdfText.ms(s.p99()) + ".");
        }
        if (r.steps() != null && r.steps().size() > 1) {
            r.steps().stream().filter(st -> st.p95() != null).max(Comparator.comparingLong(StepReportEntry::p95))
                    .ifPresent(st -> out.add("Étape la plus lente : « " + st.stepName() + " » (P95 " + PdfText.ms(st.p95()) + ")."));
        }
        if (tl != null && tl.stepEvolution() != null && tl.stepEvolution().comparable() && tl.stepEvolution().steps() != null) {
            List<String> degraded = tl.stepEvolution().steps().stream().filter(st -> "DEGRADED".equals(st.trend()))
                    .map(st -> "« " + st.name() + " »").toList();
            List<String> watch = tl.stepEvolution().steps().stream().filter(st -> "WATCH".equals(st.trend()))
                    .map(st -> "« " + st.name() + " »").toList();
            if (!degraded.isEmpty()) out.add("Temps de réponse dégradés entre le début et la fin du test : " + String.join(", ", degraded) + ".");
            else if (!watch.isEmpty()) out.add("Légère hausse des temps de réponse à surveiller : " + String.join(", ", watch) + ".");
            else out.add("Temps de réponse stables entre le début et la fin du test.");
        }
        if (s != null && s.failedRequests() > 0 && r.errors() != null && !r.errors().isEmpty()) {
            var top = ReportPdfWriter.groupErrors(r.errors()).get(0);
            out.add("Erreur la plus fréquente : " + top.getKey().get(2) + (top.getKey().get(1).equals("—") ? "" : " (HTTP " + top.getKey().get(1) + ")")
                    + " sur « " + top.getKey().get(0) + " », " + PdfText.integer(top.getValue()) + " fois.");
        } else if (s != null && s.totalRequests() > 0 && s.failedRequests() == 0) {
            out.add("Aucune erreur : toutes les requêtes ont reçu la réponse attendue.");
        }
        ExecutionTimelineResponse.ResourceSeries res = tl == null ? null : tl.resources();
        if (res != null && res.cpuPercent() != null && res.cpuPercent().stream().anyMatch(Objects::nonNull)) {
            double cpu = res.cpuPercent().stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
            double mem = res.memoryMb() == null ? 0 : res.memoryMb().stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
            out.add("Application testée : CPU maximal " + PdfText.decimal(cpu, 1) + " % d’un cœur, mémoire maximale " + PdfText.decimal(mem, 1) + " Mo.");
        }
        return out;
    }

    private static String resultLine(ExecutionReportResponse r, ExecutionReportResponse.Thresholds th) {
        String measures = th == null ? "" : " : taux d’erreurs " + PdfText.percent(th.errorRatePercent()) + " (maximum "
                + PdfText.percent(th.maxErrorRatePercent()) + ")" + (th.maxP95Ms() == null || th.maxP95Ms() == 0 ? ""
                : ", P95 " + PdfText.ms(th.p95Ms()) + " (maximum " + PdfText.ms(th.maxP95Ms()) + ")");
        if (r.status() == ExecutionStatus.SUCCESS) return "Test réussi" + measures + ".";
        if (r.status() == ExecutionStatus.THRESHOLDS_EXCEEDED) {
            List<String> over = new ArrayList<>();
            if (th != null && !th.errorRateOk()) over.add("taux d’erreurs");
            if (th != null && Boolean.FALSE.equals(th.p95Ok())) over.add("P95");
            return "Seuils dépassés (" + (over.isEmpty() ? "seuil de réussite" : String.join(" et ", over)) + ")" + measures + ".";
        }
        if (r.status() == ExecutionStatus.FAILED) return "Le test a échoué : il n’a pas pu se dérouler normalement.";
        if (r.status() == ExecutionStatus.CANCELLED) return "Le test a été annulé avant la fin : les résultats sont partiels" + measures + ".";
        return "Test non terminé.";
    }
}
