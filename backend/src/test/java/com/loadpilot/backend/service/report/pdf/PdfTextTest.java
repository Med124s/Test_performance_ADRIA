package com.loadpilot.backend.service.report.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.enums.ExecutionStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Textes du PDF : nombres a la francaise, caracteres hors police remplaces, nom de fichier sans accent. */
class PdfTextTest {

    private static final String NBSP = " ";

    @Test
    void numbers_frenchFormat() {
        assertThat(PdfText.integer(4510)).isEqualTo("4" + NBSP + "510");
        assertThat(PdfText.integer(1234567)).isEqualTo("1" + NBSP + "234" + NBSP + "567");
        assertThat(PdfText.decimal(36.723, 1)).isEqualTo("36,7");
        assertThat(PdfText.decimal(12.0, 2)).isEqualTo("12");
        assertThat(PdfText.decimal(12345.678, 1)).isEqualTo("12" + NBSP + "345,7");
        assertThat(PdfText.ms(308L)).isEqualTo("308" + NBSP + "ms");
        assertThat(PdfText.ms(null)).isEqualTo("—");
        assertThat(PdfText.duration(122_812L)).isEqualTo("2" + NBSP + "min 03" + NBSP + "s");
        assertThat(PdfText.duration(45_000L)).isEqualTo("45" + NBSP + "s");
    }

    @Test
    void safe_keepsFrenchCharacters_replacesTheOthers() {
        assertThat(PdfText.safe("Échouée « déjà » l’étape — œuvre … 5 €")).isEqualTo("Échouée « déjà » l’étape — œuvre … 5 €");
        assertThat(PdfText.safe("P95 ≤ 1\u202F000 ms → OK")).isEqualTo("P95 <= 1" + NBSP + "000 ms -> OK");
        assertThat(PdfText.safe("中")).isEqualTo("?");
    }

    @Test
    void fileName_withoutAccents() {
        ExecutionReportResponse report = new ExecutionReportResponse("id", "sc", "Parcours bancaire — été", "app", "Bank", ExecutionStatus.SUCCESS,
                Instant.parse("2026-10-10T17:13:00Z"), null, 1000L, null, 10, 20, 120, null, "u", 0L, null, List.of(), List.of(), null, null);
        assertThat(ReportPdfService.fileName(report, ZoneId.of("Africa/Casablanca"))).isEqualTo("Rapport_Parcours-bancaire-ete_2026-10-10_18h13.pdf");
    }
}
