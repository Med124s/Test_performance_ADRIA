package com.loadpilot.backend.service.report.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Exemple de rapport PDF ecrit si -Dpdf.sample=chemin est donne (aucun effet sinon). */
class ReportPdfStructureTest {

    @Test
    void writesASampleWhenAsked() throws Exception {
        String target = System.getProperty("pdf.sample");
        if (target == null || target.isBlank()) return;
        assertThat(ReportPdfSampleExport.write(Path.of(target))).exists();
    }
}
