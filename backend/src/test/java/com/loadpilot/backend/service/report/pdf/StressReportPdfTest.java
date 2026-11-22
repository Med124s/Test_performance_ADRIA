package com.loadpilot.backend.service.report.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

/** Rapport PDF d'un test de stress : mode, point de rupture, reperes du graphique et conclusion. */
class StressReportPdfTest {

    /** Texte de tout le PDF, espaces et retours normalises. */
    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder out = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) out.append(extractor.getTextFromPage(page)).append(' ');
        reader.close();
        return out.toString().replaceAll("[\\s\\u00A0\\u202F]+", " ");
    }

    @Test
    void stressReport_showsTheModeTheBreakpointAndItsMarkers() throws Exception {
        String text = text(new ReportPdfWriter().write(ReportPdfSamples.stressData()));

        assertThat(text).contains("Point de rupture (montée automatique)");
        assertThat(text).contains("Utilisateurs virtuels (pic)").doesNotContain("Ramp-up");
        assertThat(text).contains("Point de rupture vers 182 req/s offertes");
        assertThat(text).contains("Mesure au début de l’anomalie").contains("temps de réponse (P95)");
        // Libelles des graphiques dessines dans l'image : seule la note qui les explique est du texte.
        assertThat(text).contains("Zone verte : mesure de référence");
        // Pas de tableau de seuils : sans objet en mode stress.
        assertThat(text).doesNotContain("Seuil de réussite");
    }

    @Test
    void chartMarkers_baselineBand_onset_andConfirmation() {
        var markers = ReportPdfWriter.stressMarkers(ReportPdfSamples.stressData().report().stress());

        assertThat(markers).extracting(m -> m.label().replaceAll("[\\u00A0\\u202F]", " "))
                .containsExactly("Mesure de référence", "Début de la rupture (182 req/s)", "Rupture confirmée");
        assertThat(markers.get(0).band()).isTrue();
        assertThat(markers.get(0).fromSeconds()).isEqualTo(10);
        assertThat(markers.get(0).toSeconds()).isEqualTo(40);
        assertThat(markers.get(1).fromSeconds()).isEqualTo(222);
        assertThat(markers.get(2).fromSeconds()).isEqualTo(237);
        assertThat(ReportPdfWriter.stressMarkers(null)).isEmpty();
    }

    @Test
    void manualReport_isUnchanged() throws Exception {
        String text = text(new ReportPdfWriter().write(ReportPdfSamples.data(com.loadpilot.backend.enums.ExecutionStatus.THRESHOLDS_EXCEEDED, true)));

        assertThat(text).contains("Ramp-up").contains("Seuil de réussite").doesNotContain("Point de rupture").doesNotContain("Mesure de référence");
    }

    @Test
    void conclusion_startsWithTheBreakpoint() {
        var data = ReportPdfSamples.stressData();
        var lines = ReportConclusion.lines(data.report(), data.timeline());

        assertThat(lines.get(0).replaceAll("[\\u00A0\\u202F]", " "))
                .isEqualTo("Point de rupture atteint vers 182 req/s offertes et 57 utilisateur(s) virtuel(s) : temps de réponse (P95).");
        assertThat(lines.get(1)).contains("montée jusqu’à 64 utilisateur(s) virtuel(s)");
    }
}
