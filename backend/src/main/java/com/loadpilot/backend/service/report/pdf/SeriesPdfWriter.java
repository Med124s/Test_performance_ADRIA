package com.loadpilot.backend.service.report.pdf;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
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
import com.loadpilot.backend.dto.response.StressResultResponse;
import com.loadpilot.backend.dto.response.StressSeriesResponse;
import com.loadpilot.backend.enums.StressSeriesStatus;
import com.loadpilot.backend.service.execution.StressSeriesSummary;
import com.loadpilot.backend.service.execution.StressSettings;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;

/** Rapport PDF d'une serie d'essais de stress : point de rupture consolide, essais, methode. */
public final class SeriesPdfWriter {

    public record Data(StressSeriesResponse series, ZoneId zone, Instant generatedAt) {
    }

    private final BaseFont regular;
    private final BaseFont bold;
    private final Font body;
    private final Font small;
    private final Font smallMuted;
    private final Font strong;
    private final Font h2;

    public SeriesPdfWriter() {
        try {
            regular = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("Polices PDF indisponibles", e);
        }
        body = new Font(regular, 9.5f, Font.NORMAL, ReportPdfWriter.TEXT);
        small = new Font(regular, 8.5f, Font.NORMAL, ReportPdfWriter.TEXT);
        smallMuted = new Font(regular, 8f, Font.NORMAL, ReportPdfWriter.MUTED);
        strong = new Font(bold, 9.5f, Font.NORMAL, ReportPdfWriter.TEXT);
        h2 = new Font(bold, 12.5f, Font.NORMAL, ReportPdfWriter.PRIMARY_DARK);
    }

    public byte[] write(Data data) {
        StressSeriesResponse s = data.series();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 78, 46);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new HeaderFooter(data));
            doc.addTitle(PdfText.safe("Série d'essais — " + s.scenarioName()));
            doc.addAuthor("LoadPilot");
            doc.addCreator("LoadPilot");
            doc.addSubject("Série d'essais de stress : point de rupture consolidé");
            doc.open();
            title(doc, data);
            consolidated(doc, s);
            runs(doc, data);
            method(doc, s);
            doc.close();
        } catch (Exception e) {
            throw new IllegalStateException("Génération du PDF impossible : " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    private void title(Document doc, Data data) throws Exception {
        StressSeriesResponse s = data.series();
        Paragraph name = new Paragraph(PdfText.safe(s.scenarioName()), new Font(bold, 18, Font.NORMAL, ReportPdfWriter.TEXT));
        name.setSpacingAfter(1);
        doc.add(name);
        doc.add(new Paragraph(PdfText.safe("Série d'essais de stress · application : " + s.applicationName()),
                new Font(regular, 10.5f, Font.NORMAL, ReportPdfWriter.MUTED)));
        long finished = s.runs().stream().filter(r -> !r.status().isActive()).count();
        PdfPTable info = new PdfPTable(new float[] {1, 1, 1, 1});
        info.setWidthPercentage(100);
        info.setSpacingBefore(10);
        info.addCell(fact("Lancée le", PdfText.dateTime(s.startedAt(), data.zone())));
        info.addCell(fact("Essais", finished + " terminé(s) sur " + s.requestedRuns() + " demandés"));
        info.addCell(fact("Lancée par", s.triggeredByUsername() != null ? s.triggeredByUsername() : "—"));
        info.addCell(fact("Série", statusLabel(s.status())));
        Integer ramp = s.summary().rampRpsPerSecond();
        info.addCell(fact("Vitesse de montée", ramp == null ? "—" : "+" + PdfText.integer(ramp) + " req/s par seconde"));
        info.addCell(fact("Pause entre deux essais", PdfText.integer(s.pauseSeconds()) + " s (serveur réservé)"));
        info.addCell(fact("Terminée le", s.finishedAt() == null ? "—" : PdfText.dateTime(s.finishedAt(), data.zone())));
        info.addCell(fact("Fuseau horaire", data.zone().getId()));
        doc.add(info);
        if (s.stopReason() != null) doc.add(note(s.stopReason()));
    }

    private void consolidated(Document doc, StressSeriesResponse s) throws Exception {
        section(doc, "Point de rupture consolidé");
        StressSeriesSummary summary = s.summary();
        Color tone = verdictColor(summary.verdict());
        PdfPTable box = new PdfPTable(new float[] {1.3f, 3});
        box.setWidthPercentage(100);
        PdfPCell badge = new PdfPCell(new Phrase(PdfText.safe(verdictTitle(summary.verdict())), new Font(bold, 11, Font.NORMAL, Color.WHITE)));
        badge.setBackgroundColor(tone);
        badge.setBorder(Rectangle.NO_BORDER);
        badge.setPadding(10);
        badge.setHorizontalAlignment(Element.ALIGN_CENTER);
        badge.setVerticalAlignment(Element.ALIGN_MIDDLE);
        box.addCell(badge);
        PdfPCell text = new PdfPCell(new Phrase(PdfText.safe(summary.message()), body));
        text.setBorderColor(tone);
        text.setPadding(10);
        text.setVerticalAlignment(Element.ALIGN_MIDDLE);
        box.addCell(text);
        doc.add(box);
        if (summary.meanRps() != null) {
            PdfPTable t = table(new float[] {1, 1, 1, 1, 1});
            header(t, "Moyenne", "Minimum", "Maximum", "Écart", "Précision");
            row(t, PdfText.rps(summary.meanRps()), PdfText.rps(summary.minRps()), PdfText.rps(summary.maxRps()),
                    summary.spreadPercent() == null ? "—" : PdfText.percent(summary.spreadPercent()),
                    summary.precisionRps() == null ? "—" : "± " + PdfText.rps(summary.precisionRps().doubleValue()));
            doc.add(t);
            doc.add(note("Débit offert au début de l'anomalie de chaque essai en rupture. Écart : (maximum − minimum) / moyenne."));
        }
    }

    private void runs(Document doc, Data data) throws Exception {
        section(doc, "Essais");
        PdfPTable t = table(new float[] {0.6f, 1.3f, 1.9f, 1.1f, 0.9f, 1.1f, 1.5f, 1.8f});
        header(t, "Essai", "Statut", "Résultat", "Début", "VUs", "Débit obtenu", "P95 (référence)", "Critère(s)");
        for (StressSeriesResponse.Run run : data.series().runs()) {
            StressResultResponse stress = run.stress();
            boolean rupture = stress != null && stress.outcome() == com.loadpilot.backend.enums.StressOutcome.BREAKPOINT;
            t.addCell(cell(run.index() + "/" + data.series().requestedRuns(), small));
            t.addCell(cell(ReportPdfWriter.statusLabel(run.status()), new Font(bold, 8.5f, Font.NORMAL, ReportPdfWriter.statusColor(run.status()))));
            t.addCell(cell(outcome(run), small));
            t.addCell(cell(rupture && stress.onsetSeconds() != null ? PdfText.axisSeconds(stress.onsetSeconds()) : "—", small));
            t.addCell(cell(stress == null ? "—" : rupture ? nullable(stress.virtualUsers()) : nullable(stress.peakVirtualUsers()), small));
            t.addCell(cell(rupture ? PdfText.rps(stress.throughputRps()) : "—", small));
            t.addCell(cell(rupture ? PdfText.ms(stress.p95Ms()) + " (" + PdfText.ms(stress.baselineP95Ms()) + ")" : "—", small));
            t.addCell(cell(rupture ? String.join(" ; ", stress.criteria().stream().map(ReportPdfWriter::criterionLabel).toList()) : "—", small));
        }
        doc.add(t);
    }

    private void method(Document doc, StressSeriesResponse s) throws Exception {
        section(doc, "Méthode");
        int ramp = StressSettings.rampOrDefault(s.summary().rampRpsPerSecond());
        doc.add(bullet("Le même scénario en mode « Point de rupture », lancé " + s.requestedRuns() + " fois de suite, avec une pause de "
                + s.pauseSeconds() + " s entre deux essais pour que l'application récupère ; le serveur reste réservé à la série."));
        doc.add(bullet("Chaque essai mesure sa propre référence (30 s à 2 req/s), puis monte de +" + ramp
                + " req/s par seconde ; le début de la rupture est connu à ± " + StressSettings.precisionRps(ramp) + " req/s près."));
        doc.add(bullet("« Confirmé » : au moins deux essais, tous en rupture, avec un écart d'au plus "
                + PdfText.decimal(StressSettings.SERIES_MAX_SPREAD_PERCENT, 0) + " % entre le plus bas et le plus haut. "
                + "Un essai non concluant (échoué, annulé, LoadPilot saturé) arrête la série et n'entre pas dans le calcul."));
        doc.add(bullet("Quelques essais montrent que le point de rupture est reproductible ; ce n'est pas un intervalle de confiance statistique."));
    }

    // ---- Libelles ----

    static String statusLabel(StressSeriesStatus status) {
        return switch (status) {
            case RUNNING -> "En cours";
            case COMPLETED -> "Terminée";
            case INTERRUPTED -> "Interrompue";
            case CANCELLED -> "Annulée";
        };
    }

    static String verdictTitle(StressSeriesSummary.Verdict verdict) {
        return switch (verdict) {
            case CONFIRMED -> "Point de rupture confirmé";
            case DISPERSED -> "Résultats dispersés";
            case NOT_CONFIRMED -> "Non confirmé";
            case NO_BREAKPOINT -> "Aucune rupture";
            case NOT_COMPARABLE -> "Non comparables";
            case NONE -> "Aucun essai concluant";
        };
    }

    private static Color verdictColor(StressSeriesSummary.Verdict verdict) {
        return switch (verdict) {
            case CONFIRMED -> ReportPdfWriter.WARNING;
            case DISPERSED -> ReportPdfWriter.DANGER;
            case NO_BREAKPOINT -> ReportPdfWriter.SUCCESS;
            default -> ReportPdfWriter.NEUTRAL;
        };
    }

    static String outcome(StressSeriesResponse.Run run) {
        StressResultResponse stress = run.stress();
        if (stress == null || stress.outcome() == null) return run.status().isActive() ? "En cours" : "Sans résultat";
        return switch (stress.outcome()) {
            case BREAKPOINT -> "Rupture vers " + PdfText.rps(stress.offeredRps());
            case NO_BREAKPOINT -> "Aucune rupture jusqu'à " + PdfText.rps(stress.peakOfferedRps());
            case BASELINE_UNUSABLE -> "Mesure de référence impossible";
            case INCONCLUSIVE -> "Non concluant (LoadPilot saturé)";
        };
    }

    private static String nullable(Integer value) {
        return value == null ? "—" : PdfText.integer(value);
    }

    // ---- Elements ----

    private void section(Document doc, String title) throws Exception {
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

    private Paragraph bullet(String text) {
        Paragraph p = new Paragraph();
        p.add(new Chunk("•  ", new Font(bold, 9.5f, Font.NORMAL, ReportPdfWriter.PRIMARY)));
        p.add(new Chunk(PdfText.safe(text), body));
        p.setIndentationLeft(10);
        p.setFirstLineIndent(-10);
        p.setSpacingAfter(3);
        return p;
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

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        t.setHeaderRows(1);
        return t;
    }

    private void header(PdfPTable t, String... titles) {
        Font f = new Font(bold, 8.5f, Font.NORMAL, ReportPdfWriter.PRIMARY_DARK);
        for (String title : titles) {
            PdfPCell c = new PdfPCell(new Phrase(PdfText.safe(title), f));
            c.setBackgroundColor(ReportPdfWriter.PRIMARY_LIGHT);
            c.setBorderColor(ReportPdfWriter.BORDER);
            c.setPadding(5);
            t.addCell(c);
        }
    }

    private void row(PdfPTable t, String... values) {
        for (String value : values) t.addCell(cell(value, small));
    }

    private PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(PdfText.safe(text), font));
        c.setBorderColor(ReportPdfWriter.BORDER);
        c.setPadding(5);
        return c;
    }

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
            cb.setColorFill(ReportPdfWriter.PRIMARY);
            cb.rectangle(0, h - 50, w, 50);
            cb.fill();
            text(cb, bold, 15, Color.WHITE, "LoadPilot", 40, h - 31, PdfContentByte.ALIGN_LEFT);
            text(cb, regular, 9, new Color(0xE0, 0xE7, 0xFF), PdfText.safe("Série d'essais de stress"),
                    40 + bold.getWidthPoint("LoadPilot", 15) + 10, h - 30.5f, PdfContentByte.ALIGN_LEFT);
            text(cb, regular, 8.5f, new Color(0xE0, 0xE7, 0xFF), PdfText.safe(data.series().scenarioName()), w - 40, h - 30.5f,
                    PdfContentByte.ALIGN_RIGHT);
            cb.setColorStroke(ReportPdfWriter.BORDER);
            cb.setLineWidth(0.5f);
            cb.moveTo(40, 34);
            cb.lineTo(w - 40, 34);
            cb.stroke();
            text(cb, regular, 7.5f, ReportPdfWriter.MUTED, PdfText.safe("Généré par LoadPilot le "
                    + PdfText.dateTime(data.generatedAt(), data.zone()) + " · série " + data.series().id()), 40, 22, PdfContentByte.ALIGN_LEFT);
            String page = "Page " + writer.getPageNumber() + " sur ";
            float pageWidth = regular.getWidthPoint(page, 7.5f);
            text(cb, regular, 7.5f, ReportPdfWriter.MUTED, page, w - 40 - 14 - pageWidth, 22, PdfContentByte.ALIGN_LEFT);
            cb.addTemplate(total, w - 40 - 14, 22);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(regular, 7.5f);
            total.setColorFill(ReportPdfWriter.MUTED);
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
