package com.loadpilot.backend.service.report.pdf;

import com.loadpilot.backend.enums.ExecutionStatus;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Genere un exemple de rapport PDF avec les donnees de test (ReportPdfSamples), pour le relire a l'oeil.
 * Lancement : mvn -o -q test-compile exec:java non requis - appeler main depuis l'IDE, ou via le test
 * ReportPdfStructureTest (propriete -Dpdf.sample=chemin).
 */
public final class ReportPdfSampleExport {

    private ReportPdfSampleExport() {
    }

    public static Path write(Path target) throws Exception {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.write(target, new ReportPdfWriter().write(ReportPdfSamples.data(ExecutionStatus.THRESHOLDS_EXCEEDED, true)));
        return target;
    }

    /** Exemple d'un test de stress (point de rupture et reperes du graphique). */
    public static Path writeStress(Path target) throws Exception {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.write(target, new ReportPdfWriter().write(ReportPdfSamples.stressData()));
        return target;
    }

    public static void main(String[] args) throws Exception {
        System.out.println(write(Path.of(args.length > 0 ? args[0] : "target/rapport-exemple.pdf")).toAbsolutePath());
    }
}
