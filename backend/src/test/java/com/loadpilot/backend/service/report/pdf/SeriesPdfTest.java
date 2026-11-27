package com.loadpilot.backend.service.report.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.dto.response.StressResultResponse;
import com.loadpilot.backend.dto.response.StressSeriesResponse;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.StressOutcome;
import com.loadpilot.backend.enums.StressSeriesStatus;
import com.loadpilot.backend.service.execution.StressSeriesSummary;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rapport PDF d'une serie d'essais : point de rupture consolide, essais et methode. */
class SeriesPdfTest {

    private static final Instant START = Instant.parse("2026-11-27T10:00:00Z");

    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder out = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) out.append(extractor.getTextFromPage(page)).append(' ');
        reader.close();
        return out.toString().replaceAll("[\\s\\u00A0\\u202F]+", " ");
    }

    private static StressSeriesResponse.Run rupture(int index, double rps, int vus) {
        StressResultResponse stress = new StressResultResponse(StressOutcome.BREAKPOINT, null, null, vus + 5, rps + 15,
                List.of("RESPONSE_TIME"), 100, 115, vus, rps, rps - 4, 942L, 308L, 0.0, 1);
        return new StressSeriesResponse.Run(index, "e" + index, ExecutionStatus.THRESHOLDS_EXCEEDED, START.plusSeconds(150L * index),
                115_000L, stress);
    }

    @Test
    void completedSeries_consolidatedBreakpoint_runs_andMethod() throws Exception {
        List<StressSeriesResponse.Run> runs = List.of(rupture(1, 63, 33), rupture(2, 60, 31), rupture(3, 66, 35));
        StressSeriesSummary summary = StressSeriesSummary.of(runs.stream()
                .map(r -> new StressSeriesSummary.Run(r.stress().outcome(), r.stress().offeredRps(), r.stress().peakOfferedRps(), 1)).toList());
        StressSeriesResponse series = new StressSeriesResponse("s1", "sc", "Démo — point de rupture", "Démo stress", 3,
                StressSeriesStatus.COMPLETED, null, "m.benyghil", START, START.plusSeconds(480), null, 30, null, runs, summary);

        String text = text(new SeriesPdfWriter().write(new SeriesPdfWriter.Data(series, ZoneId.of("Africa/Casablanca"), START.plusSeconds(600))));

        assertThat(text).contains("Démo — point de rupture").contains("Série d'essais de stress · application : Démo stress");
        assertThat(text).contains("3 terminé(s) sur 3 demandés").contains("+1 req/s par seconde").contains("30 s (serveur réservé)");
        assertThat(text).contains("Point de rupture consolidé").contains("Point de rupture confirmé")
                .contains("Point de rupture confirmé autour de 63 req/s, entre 60 et 66 req/s sur 3 essais");
        assertThat(text).contains("± 5 req/s");
        assertThat(text).contains("Rupture vers 60 req/s").contains("942 ms (308 ms)").contains("temps de réponse (P95)");
        assertThat(text).contains("Méthode").contains("au plus 20 %").contains("ce n'est pas un intervalle de confiance statistique");
    }

    @Test
    void interruptedSeries_showsWhyItStopped() throws Exception {
        StressSeriesResponse.Run failed = new StressSeriesResponse.Run(2, "e2", ExecutionStatus.FAILED, START, 40_000L,
                new StressResultResponse(StressOutcome.BASELINE_UNUSABLE, null, null, 1, 2.0, List.of(), null, null, null, null, null,
                        null, null, null, 1));
        List<StressSeriesResponse.Run> runs = List.of(rupture(1, 63, 33), failed);
        StressSeriesSummary summary = StressSeriesSummary.of(List.of(new StressSeriesSummary.Run(StressOutcome.BREAKPOINT, 63.0, 78.0, 1),
                new StressSeriesSummary.Run(StressOutcome.BASELINE_UNUSABLE, null, 2.0, 1)));
        StressSeriesResponse series = new StressSeriesResponse("s2", "sc", "Démo", "Démo stress", 3, StressSeriesStatus.INTERRUPTED,
                "Essai 2/3 échoué : Baseline impossible.", "m.benyghil", START, START.plusSeconds(300), null, 30, null, runs, summary);

        String text = text(new SeriesPdfWriter().write(new SeriesPdfWriter.Data(series, ZoneId.of("UTC"), START.plusSeconds(600))));

        assertThat(text).contains("Interrompue").contains("Essai 2/3 échoué : Baseline impossible.");
        assertThat(text).contains("Non confirmé").contains("un seul essai concluant");
        assertThat(text).contains("Mesure de référence impossible");
    }
}
