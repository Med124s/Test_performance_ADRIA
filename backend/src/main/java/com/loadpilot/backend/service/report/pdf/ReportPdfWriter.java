package com.loadpilot.backend.service.report.pdf;

import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.dto.response.StressResultResponse;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.StressOutcome;
import com.loadpilot.backend.service.audit.AuditSanitizer;
import com.loadpilot.backend.service.execution.StressSettings;
import com.loadpilot.backend.service.execution.TestRunMasking;
import com.loadpilot.backend.service.report.ExecutionStatistics;
import com.loadpilot.backend.service.report.ReportErrorEntry;
import com.loadpilot.backend.service.report.StepReportEntry;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Mise en page du rapport PDF d'une execution terminee. */
public final class ReportPdfWriter {

    /** Donnees du rapport ; timeline null si l'analyse dans le temps est indisponible. */
    public record Data(ExecutionReportResponse report, ExecutionTimelineResponse timeline, String statusReason,
                       ZoneId zone, Instant generatedAt) {
    }

    static final Color PRIMARY = new Color(0x4F, 0x46, 0xE5);
    static final Color PRIMARY_LIGHT = new Color(0xEE, 0xF2, 0xFF);
    static final Color PRIMARY_DARK = new Color(0x31, 0x2E, 0x81);
    static final Color TEXT = new Color(0x11, 0x18, 0x27);
    static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    static final Color BORDER = new Color(0xE5, 0xE7, 0xEB);
    static final Color SUCCESS = new Color(0x16, 0xA3, 0x4A);
    static final Color WARNING = new Color(0xD9, 0x77, 0x06);
    static final Color DANGER = new Color(0xDC, 0x26, 0x26);
    static final Color NEUTRAL = new Color(0x6B, 0x72, 0x80);
    static final Color CYAN = new Color(0x08, 0x91, 0xB2);
    /** Zone de la baseline d'un test de stress (vert tres clair, sous les courbes). */
    static final Color BASELINE_BAND = new Color(0xDC, 0xFC, 0xE7);

    /** Document et redacteur de l'export en cours. */
    private Document doc;
    private PdfWriter pdfWriter;
    private final BaseFont regular;
    private final BaseFont bold;
    private final Font body;
    private final Font small;
    private final Font smallMuted;
    private final Font strong;
    private final Font h2;

    public ReportPdfWriter() {
        try {
            regular = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("Polices PDF indisponibles", e);
        }
        body = new Font(regular, 9.5f, Font.NORMAL, TEXT);
        small = new Font(regular, 8.5f, Font.NORMAL, TEXT);
        smallMuted = new Font(regular, 8f, Font.NORMAL, MUTED);
        strong = new Font(bold, 9.5f, Font.NORMAL, TEXT);
        h2 = new Font(bold, 12.5f, Font.NORMAL, PRIMARY_DARK);
    }

    public byte[] write(Data data) {
        ExecutionReportResponse r = data.report();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 78, 46);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            // Texte et graphiques toujours dans l'ordre.
            writer.setStrictImageSequence(true);
            writer.setPageEvent(new HeaderFooter(data));
            this.doc = doc;
            this.pdfWriter = writer;
            doc.addTitle(PdfText.safe("Rapport — " + r.scenarioName()));
            doc.addAuthor("LoadPilot");
            doc.addCreator("LoadPilot");
            doc.addSubject("Rapport de test de performance");
            doc.open();

            title(doc, data);
            result(doc, data);
            responseTimes(doc, r.statistics());
            if (r.retention() != null && r.retention().fromSnapshot()) {
                doc.add(note("Rapport figé : le détail des requêtes a été supprimé le "
                        + PdfText.dateTime(r.retention().requestDetailsPurgedAt() != null ? r.retention().requestDetailsPurgedAt()
                        : r.retention().resultsPurgedAt(), data.zone()) + ". Le résumé enregistré à la fin du test est conservé."));
            }
            timeline(doc, writer, data.timeline(), stressMarkers(r.stress()));
            stepEvolution(doc, data.timeline());
            steps(doc, r.steps());
            errors(doc, r.errors(), r.statistics());
            resources(doc, writer, data.timeline());
            conclusion(doc, data);
            doc.close();
        } catch (Exception e) {
            throw new IllegalStateException("Génération du PDF impossible : " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    // ---- Sections ----

    private void title(Document doc, Data data) throws Exception {
        ExecutionReportResponse r = data.report();
        Paragraph name = new Paragraph(PdfText.safe(r.scenarioName()), new Font(bold, 18, Font.NORMAL, TEXT));
        name.setSpacingAfter(1);
        doc.add(name);
        doc.add(new Paragraph(PdfText.safe("Application : " + nz(r.applicationName())), new Font(regular, 10.5f, Font.NORMAL, MUTED)));

        PdfPTable info = new PdfPTable(new float[] {1, 1, 1, 1});
        info.setWidthPercentage(100);
        info.setSpacingBefore(10);
        info.addCell(fact("Lancé le", PdfText.dateTime(r.startedAt(), data.zone())));
        info.addCell(fact("Durée", PdfText.duration(r.duration())
                + (r.pausedDurationMs() > 0 ? " (dont pause " + PdfText.duration(r.pausedDurationMs()) + ")" : "")));
        info.addCell(fact("Lancé par", r.triggeredByUsername() != null ? r.triggeredByUsername() : "Planification"));
        info.addCell(fact("Fuseau horaire", data.zone().getId()));
        StressResultResponse stress = r.stress();
        if (stress != null) {
            // Mode stress : pas de charge saisie, mais le plus haut niveau atteint par la montee automatique.
            info.addCell(fact("Mode de charge", "Point de rupture (montée automatique)"));
            info.addCell(fact("Utilisateurs virtuels (pic)", stress.peakVirtualUsers() == null ? "—" : PdfText.integer(stress.peakVirtualUsers())));
            info.addCell(fact("Débit offert (pic)", PdfText.rps(stress.peakOfferedRps())));
            info.addCell(fact("Fin de la montée", stressEnd(stress)));
        } else {
            info.addCell(fact("Utilisateurs virtuels", r.virtualUsers() == null ? "—" : PdfText.integer(r.virtualUsers())));
            info.addCell(fact("Ramp-up", r.rampUpSeconds() == null ? "—" : PdfText.integer(r.rampUpSeconds()) + " s"));
            info.addCell(fact("Durée prévue", r.durationSeconds() == null ? "Sans limite de temps" : PdfText.integer(r.durationSeconds()) + " s"));
            info.addCell(fact("Itérations", r.iterations() == null ? "Sans limite" : PdfText.integer(r.iterations()) + " par utilisateur"));
        }
        doc.add(info);
    }

    /** Ce qui a mis fin a un test de stress. */
    static String stressEnd(StressResultResponse stress) {
        if (stress.outcome() == null) return "Interrompue";
        return switch (stress.outcome()) {
            case BREAKPOINT -> "Point de rupture";
            case NO_BREAKPOINT -> stress.limit() == null ? "Limite de LoadPilot" : switch (stress.limit()) {
                case "TARGET_RPS" -> "Débit cible maximal";
                case "VIRTUAL_USERS" -> "Utilisateurs virtuels maximum";
                case "DURATION" -> "Durée maximale";
                default -> "Limite de LoadPilot";
            };
            case BASELINE_UNUSABLE -> "Mesure de référence impossible";
            case INCONCLUSIVE -> "LoadPilot saturé";
        };
    }

    /** Libelles des criteres de rupture. */
    static String criterionLabel(String criterion) {
        return switch (criterion) {
            case "RESPONSE_TIME" -> "temps de réponse (P95)";
            case "REFUSALS" -> "refus (délais, connexions refusées, 429, 502/503/504)";
            case "THROUGHPUT" -> "débit obtenu sous le débit offert";
            default -> criterion;
        };
    }

    private void result(Document doc, Data data) throws Exception {
        ExecutionReportResponse r = data.report();
        section(doc, "Résultat");
        Color tone = statusColor(r.status());
        PdfPTable box = new PdfPTable(new float[] {1.1f, 3});
        box.setWidthPercentage(100);
        PdfPCell badge = new PdfPCell(new Phrase(PdfText.safe(statusLabel(r.status())), new Font(bold, 12, Font.NORMAL, Color.WHITE)));
        badge.setBackgroundColor(tone);
        badge.setBorder(Rectangle.NO_BORDER);
        badge.setPadding(10);
        badge.setHorizontalAlignment(Element.ALIGN_CENTER);
        badge.setVerticalAlignment(Element.ALIGN_MIDDLE);
        box.addCell(badge);
        Paragraph why = new Paragraph();
        why.add(new Chunk(PdfText.safe(data.statusReason() != null ? data.statusReason() : statusSentence(r.status())), body));
        if (r.errorMessage() != null && r.status() == ExecutionStatus.FAILED && !Objects.equals(r.errorMessage(), data.statusReason())) {
            why.add(Chunk.NEWLINE);
            why.add(new Chunk(PdfText.safe(AuditSanitizer.clean(r.errorMessage())), smallMuted));
        }
        PdfPCell text = new PdfPCell(why);
        text.setBorderColor(tone);
        text.setPadding(10);
        text.setVerticalAlignment(Element.ALIGN_MIDDLE);
        box.addCell(text);
        doc.add(box);

        StressResultResponse stress = r.stress();
        if (stress != null && stress.message() != null) {
            doc.add(note(AuditSanitizer.clean(stress.message())));
        }
        if (stress != null && stress.outcome() == StressOutcome.BREAKPOINT) {
            PdfPTable t = table(new float[] {2.2f, 3});
            header(t, "Point de rupture", "Mesure au début de l’anomalie");
            row(t, "Critère(s) confirmé(s)", String.join(" ; ", stress.criteria().stream().map(ReportPdfWriter::criterionLabel).toList()));
            row(t, "Début de l’anomalie", stress.onsetSeconds() == null ? "—" : "à " + PdfText.axisSeconds(stress.onsetSeconds())
                    + (stress.detectedSeconds() == null ? "" : " (confirmée à " + PdfText.axisSeconds(stress.detectedSeconds()) + ")"));
            row(t, "Charge", (stress.virtualUsers() == null ? "—" : PdfText.integer(stress.virtualUsers())) + " utilisateur(s) virtuel(s), "
                    + PdfText.rps(stress.offeredRps()) + " offertes");
            row(t, "Débit obtenu", PdfText.rps(stress.throughputRps()));
            row(t, "P95", PdfText.ms(stress.p95Ms()) + " (référence " + PdfText.ms(stress.baselineP95Ms()) + ")");
            row(t, "Refus", PdfText.percent(stress.refusalPercent()));
            doc.add(t);
        }

        ExecutionReportResponse.Thresholds th = r.thresholds();
        if (th != null) {
            PdfPTable t = table(new float[] {2.2f, 1.3f, 1.3f, 1});
            header(t, "Seuil de réussite", "Mesuré", "Maximum", "Respecté");
            row(t, "Taux d’erreurs", PdfText.percent(th.errorRatePercent()), PdfText.percent(th.maxErrorRatePercent()), ok(th.errorRateOk()));
            row(t, "P95 (95 % des requêtes plus rapides que)", PdfText.ms(th.p95Ms()),
                    th.maxP95Ms() == null || th.maxP95Ms() == 0 ? "Aucun seuil" : PdfText.ms(th.maxP95Ms()),
                    th.p95Ok() == null ? "—" : ok(th.p95Ok()));
            doc.add(t);
        }
    }

    private void responseTimes(Document doc, ExecutionStatistics s) throws Exception {
        section(doc, "Chiffres clés");
        if (s == null) {
            doc.add(note("Aucune requête mesurée."));
            return;
        }
        PdfPTable cards = new PdfPTable(4);
        cards.setWidthPercentage(100);
        cards.addCell(card("Requêtes", PdfText.integer(s.totalRequests()), PdfText.integer(s.failedRequests()) + " en échec"));
        cards.addCell(card("Taux d’erreurs", PdfText.percent(num(s.errorRate())), "Réussite : " + PdfText.percent(num(s.successRate()))));
        cards.addCell(card("Débit moyen", PdfText.rps(num(s.throughput())), "hors temps de pause"));
        cards.addCell(card("Temps moyen", PdfText.ms(s.avgResponseTime()), "min " + PdfText.ms(s.minResponseTime()) + " · max " + PdfText.ms(s.maxResponseTime())));
        cards.addCell(card("P50 (médiane)", PdfText.ms(s.p50()), "la moitié des requêtes"));
        cards.addCell(card("P90", PdfText.ms(s.p90()), "90 % des requêtes"));
        cards.addCell(card("P95", PdfText.ms(s.p95()), "95 % des requêtes"));
        cards.addCell(card("P99", PdfText.ms(s.p99()), "99 % des requêtes"));
        doc.add(cards);
    }

    /** Test de stress : zone de la baseline, debut de l'anomalie et confirmation de la rupture ; vide sinon. */
    static List<PdfCharts.Marker> stressMarkers(StressResultResponse stress) {
        if (stress == null) return List.of();
        List<PdfCharts.Marker> markers = new ArrayList<>();
        markers.add(new PdfCharts.Marker(StressSettings.WARMUP_SECONDS, StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS,
                "Mesure de référence", BASELINE_BAND));
        if (stress.outcome() == StressOutcome.BREAKPOINT && stress.onsetSeconds() != null) {
            markers.add(new PdfCharts.Marker(stress.onsetSeconds(), stress.onsetSeconds(),
                    "Début de la rupture (" + PdfText.rps(stress.offeredRps()) + ")", WARNING));
            if (stress.detectedSeconds() != null) {
                markers.add(new PdfCharts.Marker(stress.detectedSeconds(), stress.detectedSeconds(), "Rupture confirmée", DANGER));
            }
        }
        return markers;
    }

    private void timeline(Document doc, PdfWriter writer, ExecutionTimelineResponse tl, List<PdfCharts.Marker> markers) throws Exception {
        section(doc, "Analyse dans le temps");
        if (tl == null || tl.t() == null || tl.t().isEmpty()) {
            doc.add(note("Analyse dans le temps indisponible pour cette exécution."));
            return;
        }
        if (!markers.isEmpty()) {
            doc.add(note("Zone verte : mesure de référence (" + StressSettings.BASELINE_SECONDS + " s à " + StressSettings.BASELINE_RPS
                    + " req/s). Lignes pointillées : début de l’anomalie et confirmation de la rupture."));
        }
        List<Double> x = tl.t().stream().map(Integer::doubleValue).toList();
        float w = PageSize.A4.getWidth() - 80;
        List<PdfCharts.Series> load = new ArrayList<>();
        load.add(new PdfCharts.Series("Débit (req/s)", PRIMARY, tl.throughput(), PdfCharts.Axis.LEFT));
        if (tl.virtualUsersAvailable() && tl.activeVirtualUsers() != null) {
            load.add(new PdfCharts.Series("Utilisateurs actifs (axe de droite)", CYAN, tl.activeVirtualUsers(), PdfCharts.Axis.RIGHT));
        }
        doc.add(chart("Charge : débit et utilisateurs actifs",
                PdfCharts.lines(writer, regular, w, 130, x, load, v -> PdfText.decimal(v, 1), v -> PdfText.integer(Math.round(v)), markers), w));
        doc.add(chart("Temps de réponse", PdfCharts.lines(writer, regular, w, 130, x, List.of(
                new PdfCharts.Series("Temps moyen", CYAN, tl.avgMs(), PdfCharts.Axis.LEFT),
                new PdfCharts.Series("P95", WARNING, tl.p95Ms(), PdfCharts.Axis.LEFT)),
                v -> PdfText.integer(Math.round(v)) + " ms", null, markers), w));

        long totalErrors = tl.errors() == null ? 0 : tl.errors().stream().filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
        if (totalErrors > 0) {
            doc.add(chart("Erreurs par intervalle de " + tl.bucketSeconds() + " s",
                    PdfCharts.bars(writer, regular, w, 100, x, tl.errors(), "Erreurs", DANGER, tl.bucketSeconds()), w));
        } else {
            doc.add(note("Aucune erreur pendant le test."));
        }
        if (tl.pauses() != null && !tl.pauses().isEmpty()) {
            doc.add(note("Le test a été mis en pause " + tl.pauses().size() + " fois : les courbes sont interrompues pendant les pauses."));
        }
    }

    private void stepEvolution(Document doc, ExecutionTimelineResponse tl) throws Exception {
        if (tl == null || tl.stepEvolution() == null) return;
        section(doc, "Évolution des étapes pendant le test");
        ExecutionTimelineResponse.StepEvolution ev = tl.stepEvolution();
        if (!ev.comparable() || ev.steps() == null || ev.steps().isEmpty()) {
            doc.add(note(ev.message() != null ? ev.message() : "Test trop court pour comparer le début et la fin."));
            return;
        }
        doc.add(note("P95 de chaque étape au début (" + PdfText.axisSeconds(ev.startFromSeconds()) + " à " + PdfText.axisSeconds(ev.startToSeconds())
                + ") et à la fin du test (" + PdfText.axisSeconds(ev.endFromSeconds()) + " à " + PdfText.axisSeconds(ev.endToSeconds()) + ")."));
        PdfPTable t = table(new float[] {3, 1.1f, 1.1f, 1.3f, 1.4f});
        header(t, "Étape", "P95 début", "P95 fin", "Écart", "Tendance");
        List<ExecutionTimelineResponse.StepTrend> sorted = new ArrayList<>(ev.steps());
        sorted.sort(Comparator.comparingInt((ExecutionTimelineResponse.StepTrend s) -> trendRank(s.trend())));
        for (ExecutionTimelineResponse.StepTrend s : sorted) {
            String delta = s.deltaMs() == null ? "—" : (s.deltaMs() > 0 ? "+" : "") + PdfText.integer(s.deltaMs()) + " ms"
                    + (s.deltaPercent() == null ? "" : " (" + (s.deltaPercent() > 0 ? "+" : "") + PdfText.decimal(s.deltaPercent(), 0) + " %)");
            t.addCell(cell(stepLabel(s.method() == null ? null : s.method(), s.name()), small));
            t.addCell(num(PdfText.ms(s.startP95Ms())));
            t.addCell(num(PdfText.ms(s.endP95Ms())));
            t.addCell(num(delta));
            PdfPCell trend = cell(trendLabel(s.trend()), new Font(bold, 8.5f, Font.NORMAL, trendColor(s.trend())));
            t.addCell(trend);
        }
        doc.add(t);
    }

    private void steps(Document doc, List<StepReportEntry> steps) throws Exception {
        section(doc, "Détail par étape");
        if (steps == null || steps.isEmpty()) {
            doc.add(note("Aucune étape mesurée."));
            return;
        }
        PdfPTable t = table(new float[] {3.4f, 1, 0.9f, 1, 1, 1});
        header(t, "Étape", "Requêtes", "Échecs", "Moyenne", "P95", "P99");
        for (StepReportEntry s : steps) {
            t.addCell(stepCell(s));
            t.addCell(num(PdfText.integer(s.total())));
            t.addCell(num(PdfText.integer(s.failed()), s.failed() > 0 ? DANGER : TEXT));
            t.addCell(num(PdfText.ms(s.avgResponseTime())));
            t.addCell(num(PdfText.ms(s.p95())));
            t.addCell(num(PdfText.ms(s.p99())));
        }
        doc.add(t);
    }

    private void errors(Document doc, List<ReportErrorEntry> errors, ExecutionStatistics stats) throws Exception {
        section(doc, "Erreurs");
        if (errors == null || errors.isEmpty()) {
            doc.add(note(stats != null && stats.failedRequests() > 0
                    ? "Des requêtes ont échoué, mais leur détail n’est plus disponible."
                    : "Aucune erreur : toutes les requêtes ont reçu la réponse attendue."));
            return;
        }
        List<Map.Entry<List<String>, Integer>> groups = groupErrors(errors);
        PdfPTable t = table(new float[] {2.3f, 0.8f, 3.6f, 0.8f});
        header(t, "Étape", "Code", "Erreur", "Nombre");
        groups.stream().limit(15).forEach(g -> {
            t.addCell(cell(g.getKey().get(0), small));
            t.addCell(num(g.getKey().get(1)));
            t.addCell(cell(g.getKey().get(2), small));
            t.addCell(num(PdfText.integer(g.getValue()), DANGER));
        });
        doc.add(t);
        if (groups.size() > 15) doc.add(note((groups.size() - 15) + " autre(s) type(s) d’erreur non affiché(s) : voir le rapport en ligne."));
    }

    private void resources(Document doc, PdfWriter writer, ExecutionTimelineResponse tl) throws Exception {
        section(doc, "CPU et mémoire de l’application testée");
        ExecutionTimelineResponse.ResourceSeries res = tl == null ? null : tl.resources();
        if (res == null || res.sampleCount() == 0 || res.cpuPercent() == null || res.cpuPercent().isEmpty()) {
            doc.add(note(res != null && res.message() != null ? res.message()
                    : "Aucune mesure : le monitoring n’est pas configuré pour cette application (ou était indisponible)."));
            return;
        }
        double cpuMax = res.cpuPercent().stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
        double memMax = res.memoryMb() == null ? 0 : res.memoryMb().stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0);
        doc.add(note("CPU maximal : " + PdfText.decimal(cpuMax, 1) + " % d’un cœur · mémoire maximale : " + PdfText.decimal(memMax, 1)
                + " Mo · une mesure toutes les " + res.intervalSeconds() + " s (" + PdfText.integer(res.sampleCount()) + " mesures)."));
        // Mesures alignees sur les intervalles de l'analyse dans le temps (null entre deux mesures).
        List<Double> x = tl.t().stream().map(Integer::doubleValue).toList();
        float w = PageSize.A4.getWidth() - 80;
        List<PdfCharts.Series> series = new ArrayList<>();
        series.add(new PdfCharts.Series("CPU (% d’un cœur)", PRIMARY, res.cpuPercent(), PdfCharts.Axis.LEFT, true));
        if (res.memoryMb() != null && !res.memoryMb().isEmpty()) {
            series.add(new PdfCharts.Series("Mémoire (Mo, axe de droite)", SUCCESS, res.memoryMb(), PdfCharts.Axis.RIGHT, true));
        }
        doc.add(chart("CPU et mémoire pendant le test",
                PdfCharts.lines(writer, regular, w, 120, x, series, v -> PdfText.decimal(v, 0) + " %", v -> PdfText.decimal(v, 0)), w));
    }

    private void conclusion(Document doc, Data data) throws Exception {
        List<String> insights = data.timeline() == null || data.timeline().insights() == null ? List.of()
                : data.timeline().insights().stream().map(ExecutionTimelineResponse.Insight::text).filter(Objects::nonNull).toList();
        if (!insights.isEmpty()) {
            section(doc, "Points clés");
            for (String text : insights) doc.add(bullet(text));
        }
        section(doc, "Conclusion");
        for (String line : ReportConclusion.lines(data.report(), data.timeline())) doc.add(bullet(line));
    }

    // ---- Elements ----

    private void section(Document doc, String title) throws Exception {
        // Moins de 150 points sous le titre : la section commence sur la page suivante.
        if (pdfWriter != null && pdfWriter.getVerticalPosition(true) - doc.bottom() < 150) doc.newPage();
        Paragraph p = new Paragraph(PdfText.safe(title), h2);
        p.setSpacingBefore(14);
        p.setSpacingAfter(6);
        p.setKeepTogether(true);
        doc.add(p);
    }

    private Paragraph note(String text) {
        Paragraph p = new Paragraph(PdfText.safe(text), smallMuted);
        p.setSpacingBefore(3);
        p.setSpacingAfter(3);
        return p;
    }

    private Paragraph chartTitle(String text) {
        Paragraph p = new Paragraph(PdfText.safe(text), new Font(bold, 9.5f, Font.NORMAL, TEXT));
        p.setSpacingBefore(6);
        p.setSpacingAfter(2);
        return p;
    }

    private Paragraph bullet(String text) {
        Paragraph p = new Paragraph();
        p.add(new Chunk("•  ", new Font(bold, 9.5f, Font.NORMAL, PRIMARY)));
        p.add(new Chunk(PdfText.safe(AuditSanitizer.clean(text)), body));
        p.setIndentationLeft(10);
        p.setFirstLineIndent(-10);
        p.setSpacingAfter(3);
        return p;
    }

    /** Titre et graphique dans un meme bloc, jamais separes par un saut de page. */
    private PdfPTable chart(String title, Image image, float width) {
        image.scaleToFit(width, image.getHeight());
        PdfPTable block = new PdfPTable(1);
        block.setWidthPercentage(100);
        block.setKeepTogether(true);
        block.setSpacingBefore(6);
        block.setSpacingAfter(4);
        PdfPCell head = new PdfPCell(chartTitle(title));
        head.setBorder(Rectangle.NO_BORDER);
        head.setPadding(0);
        head.setPaddingBottom(3);
        block.addCell(head);
        PdfPCell body = new PdfPCell(image, false);
        body.setBorder(Rectangle.NO_BORDER);
        body.setPadding(0);
        block.addCell(body);
        return block;
    }

    private PdfPCell fact(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(PdfText.safe(label) + "\n", smallMuted));
        p.add(new Chunk(PdfText.safe(value), strong));
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingBottom(7);
        c.setPaddingLeft(0);
        return c;
    }

    private PdfPCell card(String label, String value, String hint) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(PdfText.safe(label) + "\n", smallMuted));
        p.add(new Chunk(PdfText.safe(value) + "\n", new Font(bold, 13, Font.NORMAL, TEXT)));
        p.add(new Chunk(PdfText.safe(hint), smallMuted));
        PdfPCell c = new PdfPCell(p);
        c.setBorderColor(BORDER);
        c.setPadding(8);
        return c;
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        t.setHeaderRows(1);
        return t;
    }

    private void header(PdfPTable t, String... titles) {
        Font f = new Font(bold, 8.5f, Font.NORMAL, PRIMARY_DARK);
        for (int i = 0; i < titles.length; i++) {
            PdfPCell c = new PdfPCell(new Phrase(PdfText.safe(titles[i]), f));
            c.setBackgroundColor(PRIMARY_LIGHT);
            c.setBorderColor(BORDER);
            c.setPadding(5);
            if (i > 0) c.setHorizontalAlignment(Element.ALIGN_RIGHT);
            t.addCell(c);
        }
    }

    private void row(PdfPTable t, String... values) {
        for (int i = 0; i < values.length; i++) t.addCell(i == 0 ? cell(values[i], small) : num(values[i]));
    }

    private PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(PdfText.safe(text), font));
        c.setBorderColor(BORDER);
        c.setPadding(5);
        return c;
    }

    private PdfPCell num(String text) {
        return num(text, TEXT);
    }

    private PdfPCell num(String text, Color color) {
        PdfPCell c = cell(text, new Font(regular, 8.5f, Font.NORMAL, color));
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private PdfPCell stepCell(StepReportEntry s) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(PdfText.safe(s.stepName()) + "\n", new Font(bold, 8.5f, Font.NORMAL, TEXT)));
        p.add(new Chunk(PdfText.safe((s.method() == null ? "" : s.method().name() + " ") + safeUrl(s.url())), smallMuted));
        PdfPCell c = new PdfPCell(p);
        c.setBorderColor(BORDER);
        c.setPadding(5);
        return c;
    }

    // ---- Regles ----

    /** Erreurs regroupees par etape, code et message (plus frequentes d'abord), sans secret. */
    static List<Map.Entry<List<String>, Integer>> groupErrors(List<ReportErrorEntry> errors) {
        Map<List<String>, Integer> groups = new LinkedHashMap<>();
        for (ReportErrorEntry e : errors) {
            List<String> key = List.of(nz(e.stepName()), e.httpStatus() == null ? "—" : String.valueOf(e.httpStatus()),
                    e.error() == null || e.error().isBlank() ? "Réponse inattendue" : AuditSanitizer.clean(e.error()));
            groups.merge(key, 1, Integer::sum);
        }
        List<Map.Entry<List<String>, Integer>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Map.Entry.<List<String>, Integer>comparingByValue().reversed());
        return sorted;
    }

    /** URL d'une etape sans parametre sensible (cle, jeton, mot de passe...) ni valeur secrete. */
    static String safeUrl(String url) {
        if (url == null) return "";
        return AuditSanitizer.clean(TestRunMasking.maskUrl(url, Set.of(), List.of()));
    }

    public static String statusLabel(ExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> "Réussie";
            case THRESHOLDS_EXCEEDED -> "Seuils dépassés";
            case FAILED -> "Échouée";
            case CANCELLED -> "Annulée";
            case QUEUED -> "En attente";
            case RUNNING -> "En cours";
            case PAUSED -> "En pause";
        };
    }

    private static String statusSentence(ExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> "Le test s’est déroulé normalement et respecte les seuils de réussite.";
            case THRESHOLDS_EXCEEDED -> "Le test s’est déroulé, mais au moins un seuil de réussite est dépassé.";
            case FAILED -> "Le test n’a pas pu se dérouler.";
            case CANCELLED -> "Le test a été annulé : les résultats sont partiels.";
            default -> "";
        };
    }

    static Color statusColor(ExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> SUCCESS;
            case THRESHOLDS_EXCEEDED -> WARNING;
            case FAILED -> DANGER;
            default -> NEUTRAL;
        };
    }

    private static String ok(boolean ok) {
        return ok ? "Oui" : "Non — dépassé";
    }

    static String trendLabel(String trend) {
        return switch (nz(trend)) {
            case "DEGRADED" -> "Dégradée";
            case "WATCH" -> "À surveiller";
            case "STABLE" -> "Stable";
            default -> "Pas assez de données";
        };
    }

    private static int trendRank(String trend) {
        return switch (nz(trend)) {
            case "DEGRADED" -> 0;
            case "WATCH" -> 1;
            case "STABLE" -> 2;
            default -> 3;
        };
    }

    private static Color trendColor(String trend) {
        return switch (nz(trend)) {
            case "DEGRADED" -> DANGER;
            case "WATCH" -> WARNING;
            case "STABLE" -> SUCCESS;
            default -> MUTED;
        };
    }

    private static String stepLabel(String method, String name) {
        return (method == null ? "" : method + " ") + nz(name);
    }

    private static Double num(java.math.BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---- En-tete et pied de page ----

    /** Bandeau LoadPilot en haut de chaque page ; « Page X sur Y » et date de generation en bas. */
    private final class HeaderFooter extends PdfPageEventHelper {
        private final Data data;
        private PdfTemplate total;

        HeaderFooter(Data data) {
            this.data = data;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(30, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float w = document.getPageSize().getWidth();
            float h = document.getPageSize().getHeight();
            cb.setColorFill(PRIMARY);
            cb.rectangle(0, h - 50, w, 50);
            cb.fill();
            text(cb, bold, 15, Color.WHITE, "LoadPilot", 40, h - 31, PdfContentByte.ALIGN_LEFT);
            text(cb, regular, 9, new Color(0xE0, 0xE7, 0xFF), "Rapport de test de performance", 40 + bold.getWidthPoint("LoadPilot", 15) + 10,
                    h - 30.5f, PdfContentByte.ALIGN_LEFT);
            text(cb, regular, 8.5f, new Color(0xE0, 0xE7, 0xFF), PdfText.safe(data.report().scenarioName()), w - 40, h - 30.5f, PdfContentByte.ALIGN_RIGHT);

            cb.setColorStroke(BORDER);
            cb.setLineWidth(0.5f);
            cb.moveTo(40, 34);
            cb.lineTo(w - 40, 34);
            cb.stroke();
            text(cb, regular, 7.5f, MUTED, PdfText.safe("Généré par LoadPilot le " + PdfText.dateTime(data.generatedAt(), data.zone())
                    + " · exécution " + data.report().id()), 40, 22, PdfContentByte.ALIGN_LEFT);
            String page = "Page " + writer.getPageNumber() + " sur ";
            float pageWidth = regular.getWidthPoint(page, 7.5f);
            text(cb, regular, 7.5f, MUTED, page, w - 40 - 14 - pageWidth, 22, PdfContentByte.ALIGN_LEFT);
            cb.addTemplate(total, w - 40 - 14, 22);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(regular, 7.5f);
            total.setColorFill(MUTED);
            total.showText(String.valueOf(writer.getPageNumber() - 1));
            total.endText();
        }

        private void text(PdfContentByte cb, BaseFont font, float size, Color color, String text, float x, float y, int align) {
            cb.beginText();
            cb.setFontAndSize(font, size);
            cb.setColorFill(color);
            cb.showTextAligned(align, text, x, y, 0);
            cb.endText();
        }
    }
}
