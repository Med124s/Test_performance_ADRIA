package com.loadpilot.backend.service.report.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.service.report.export.ExportTable.Column;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regles de format des exports : CSV « Excel en francais », dates Excel, noms de fichiers. */
class ExportFormatTest {

    private static final ZoneId CASA = ZoneId.of("Africa/Casablanca");

    @Test
    void csv_frenchNumbers_quoting_formulaInjection_negativeNumbersKept() {
        assertThat(CsvTableWriter.number(new BigDecimal("36.723"), Column.decimal("Débit (req/s)", 1))).isEqualTo("36,7");
        assertThat(CsvTableWriter.number(2.5, Column.decimal("Taux", 2))).isEqualTo("2,50");
        assertThat(CsvTableWriter.number(4510, Column.integer("Requêtes"))).isEqualTo("4510");
        assertThat(CsvTableWriter.number(-16L, Column.integer("Écart (ms)"))).as("nombre negatif : jamais prefixe").isEqualTo("-16");
        assertThat(CsvTableWriter.text("Paiement ; carte \"Visa\"")).isEqualTo("\"Paiement ; carte \"\"Visa\"\"\"");
        assertThat(CsvTableWriter.text("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvTableWriter.text("-cmd")).isEqualTo("'-cmd");
        assertThat(CsvTableWriter.text("ligne 1\nligne 2")).isEqualTo("\"ligne 1\nligne 2\"");
        assertThat(CsvTableWriter.text("Santé de l'API")).isEqualTo("Santé de l'API");
    }

    @Test
    void csv_bomHeaderCrlf_datesInLocalTime() {
        ExportTable t = new ExportTable("Erreurs", "Erreurs", List.of(Column.integer("Code HTTP"), Column.dateTime("Première apparition")),
                List.of(Arrays.asList(500, Instant.parse("2026-10-10T17:13:41.060Z")), Arrays.asList(null, null)));
        byte[] bytes = CsvTableWriter.write(t, CASA);
        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .isEqualTo("\uFEFFCode HTTP;Première apparition\r\n500;10/10/2026 18:13:41\r\n;\r\n");
    }

    @Test
    void xlsx_excelDates_columnLetters_xmlEscaping() {
        assertThat(XlsxWriter.excelDate(Instant.parse("2026-10-10T17:13:41Z"), CASA)).isEqualTo("46305.75950231");
        assertThat(XlsxWriter.column(0)).isEqualTo("A");
        assertThat(XlsxWriter.column(25)).isEqualTo("Z");
        assertThat(XlsxWriter.column(26)).isEqualTo("AA");
        assertThat(XlsxWriter.xml("<a & \"b\">\u0001")).isEqualTo("&lt;a &amp; &quot;b&quot;&gt;");
    }

    @Test
    void fileNames_readable_withoutAccents() {
        Instant started = Instant.parse("2026-10-10T15:38:00Z");
        assertThat(ReportFileNames.of("Parcours bancaire", started, CASA, null, "xlsx")).isEqualTo("Rapport_Parcours-bancaire_2026-10-10_16h38.xlsx");
        assertThat(ReportFileNames.of("Parcours bancaire", started, CASA, "Etapes", "csv")).isEqualTo("Rapport_Parcours-bancaire_2026-10-10_16h38_Etapes.csv");
        assertThat(ReportFileNames.of("Éléments : « été » / œuvre", started, CASA, null, "pdf")).isEqualTo("Rapport_Elements-ete-ouvre_2026-10-10_16h38.pdf");
        assertThat(ReportFileNames.of("***", null, CASA, null, "pdf")).isEqualTo("Rapport_Execution.pdf");
    }
}
