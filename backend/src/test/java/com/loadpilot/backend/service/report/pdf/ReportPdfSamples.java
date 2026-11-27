package com.loadpilot.backend.service.report.pdf;

import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.dto.response.ResultsRetentionResponse;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.service.report.ExecutionStatistics;
import com.loadpilot.backend.service.report.ReportErrorEntry;
import com.loadpilot.backend.service.report.StepReportEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Jeu de donnees de test realiste pour le rapport PDF : 5 etapes bancaires, 2 400 requetes, erreurs de
 * plusieurs types, analyse dans le temps sur 5 min, CPU / RAM de l'application. Aucun secret.
 */
final class ReportPdfSamples {

    static final Instant START = Instant.parse("2026-10-21T09:00:00Z");
    static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");

    private ReportPdfSamples() {
    }

    static ReportPdfWriter.Data data(ExecutionStatus status, boolean withTimeline) {
        return new ReportPdfWriter.Data(report(status), withTimeline ? timeline() : null,
                status == ExecutionStatus.THRESHOLDS_EXCEEDED ? "Seuils dépassés : taux d'erreurs 2,1 % > 1 % ; P95 612 ms > 500 ms." : null,
                ZONE, Instant.parse("2026-10-21T10:15:00Z"));
    }

    static ExecutionReportResponse report(ExecutionStatus status) {
        ExecutionStatistics stats = new ExecutionStatistics(2400, 2350, 50, new BigDecimal("97.92"), new BigDecimal("2.08"),
                18L, 2150L, 236L, 190L, 280L, 420L, 612L, 1180L, new BigDecimal("8.163"));
        List<StepReportEntry> steps = List.of(
                new StepReportEntry("s1", "Connexion", HttpMethod.POST, "/api/auth/login", 480, 480, 0, 142L, 41L, 610L, 260L, 390L, BigDecimal.ZERO),
                new StepReportEntry("s2", "Consulter le solde", HttpMethod.GET, "/api/accounts/{id}/balance", 480, 478, 2, 98L, 18L, 520L, 190L, 310L, new BigDecimal("0.42")),
                new StepReportEntry("s3", "Historique des opérations", HttpMethod.GET, "/api/accounts/{id}/transactions?page=0&size=50", 480, 470, 10, 312L, 60L, 1450L, 690L, 1120L, new BigDecimal("2.08")),
                new StepReportEntry("s4", "Virement national", HttpMethod.POST, "/api/transfers/national", 480, 452, 28, 455L, 90L, 2150L, 980L, 1700L, new BigDecimal("5.83")),
                new StepReportEntry("s5", "Déconnexion", HttpMethod.POST, "/api/auth/logout", 480, 470, 10, 61L, 20L, 380L, 120L, 210L, new BigDecimal("2.08")));
        List<ReportErrorEntry> errors = new ArrayList<>();
        for (int i = 0; i < 20; i++) errors.add(new ReportErrorEntry("Virement national", HttpMethod.POST, "/api/transfers/national", 500,
                "Reponse HTTP 500 (attendu 201)", START.plusSeconds(60 + i * 7L)));
        for (int i = 0; i < 8; i++) errors.add(new ReportErrorEntry("Virement national", HttpMethod.POST, "/api/transfers/national", 409,
                "Reponse HTTP 409 (attendu 201)", START.plusSeconds(90 + i * 9L)));
        for (int i = 0; i < 10; i++) errors.add(new ReportErrorEntry("Historique des opérations", HttpMethod.GET, "/api/accounts/{id}/transactions",
                null, "Delai d'attente depasse (10s)", START.plusSeconds(150 + i * 5L)));
        for (int i = 0; i < 10; i++) errors.add(new ReportErrorEntry("Déconnexion", HttpMethod.POST, "/api/auth/logout", 500,
                "Reponse HTTP 500 (attendu 2xx-3xx)", START.plusSeconds(200 + i * 4L)));
        errors.add(new ReportErrorEntry("Consulter le solde", HttpMethod.GET, "/api/accounts/{id}/balance", 401,
                "Reponse HTTP 401 (attendu 200)", START.plusSeconds(30)));
        errors.add(new ReportErrorEntry("Consulter le solde", HttpMethod.GET, "/api/accounts/{id}/balance", 401,
                "Reponse HTTP 401 (attendu 200)", START.plusSeconds(31)));
        ExecutionReportResponse.Thresholds thresholds = new ExecutionReportResponse.Thresholds(1.0, 2.08, false, 500L, 612L, false);
        ResultsRetentionResponse retention = new ResultsRetentionResponse(false, null, null, false, null);
        return new ExecutionReportResponse("6f1c2a9e-0b7d-4e55-9a31-2c8d1f0e7b42", "sc-1", "Parcours bancaire complet", "app-1",
                "Banking API (recette)", status, START, START.plusSeconds(312), 312_000L,
                status == ExecutionStatus.FAILED ? "Application injoignable : aucune requête n'a reçu de réponse." : null,
                20, 30, 300, null, "m.benyghil", 12_000L, stats, steps, errors, thresholds, retention);
    }

    /** Test de stress : rupture detectee vers 182 req/s (P95), sans seuils de scenario. */
    static ReportPdfWriter.Data stressData() {
        ExecutionReportResponse base = report(ExecutionStatus.THRESHOLDS_EXCEEDED);
        com.loadpilot.backend.dto.response.StressResultResponse stress = new com.loadpilot.backend.dto.response.StressResultResponse(
                com.loadpilot.backend.enums.StressOutcome.BREAKPOINT, null,
                "Point de rupture vers 182 req/s offertes et 57 utilisateur(s) virtuel(s), à 3 min 42 s du début : P95 de 1 240 ms, "
                        + "4,1 × la baseline (300 ms) (confirmé sur 3 fenêtres de 5 s consécutives).",
                64, 197.0, List.of("RESPONSE_TIME"), 222, 237, 57, 182.0, 171.5, 1240L, 300L, 1.2, 1);
        ExecutionReportResponse r = new ExecutionReportResponse(base.id(), base.scenarioId(), base.scenarioName(), base.applicationId(),
                base.applicationName(), base.status(), base.startedAt(), base.finishedAt(), base.duration(), stress.message(), 64, 0, null, null,
                base.triggeredByUsername(), 0, base.statistics(), base.steps(), base.errors(), null, base.retention(),
                com.loadpilot.backend.enums.LoadMode.STRESS, stress);
        return new ReportPdfWriter.Data(r, timeline(), "Point de rupture : 182 req/s", ZONE, Instant.parse("2026-10-21T10:15:00Z"));
    }

    static ExecutionTimelineResponse timeline() {
        int buckets = 60;
        List<Integer> t = new ArrayList<>();
        List<Integer> vus = new ArrayList<>();
        List<Integer> requests = new ArrayList<>();
        List<Double> throughput = new ArrayList<>();
        List<Long> avg = new ArrayList<>();
        List<Long> p95 = new ArrayList<>();
        List<Integer> errors = new ArrayList<>();
        List<Double> paused = new ArrayList<>();
        List<Double> cpu = new ArrayList<>();
        List<Double> mem = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            t.add(i * 5);
            int active = Math.min(20, 1 + i * 20 / 6);
            vus.add(active);
            int req = Math.max(1, active * 2);
            requests.add(req);
            throughput.add(req / 5.0);
            avg.add(150L + i * 3L);
            p95.add(320L + i * 6L + (i > 40 ? 120L : 0L));
            errors.add(i > 10 && i % 7 == 0 ? 4 : i > 30 && i % 5 == 0 ? 2 : 0);
            paused.add(0.0);
            cpu.add(i % 2 == 0 ? 35.0 + i * 0.8 : null);
            mem.add(i % 2 == 0 ? 410.0 + i * 1.5 : null);
        }
        ExecutionTimelineResponse.StepEvolution evolution = new ExecutionTimelineResponse.StepEvolution(true, null, 270, 30, 90, 240, 300, List.of(
                new ExecutionTimelineResponse.StepTrend("s4", "Virement national", "POST", "DEGRADED", 610L, 1240L, 96, 96, 480, 630L, 103.3),
                new ExecutionTimelineResponse.StepTrend("s3", "Historique des opérations", "GET", "WATCH", 540L, 760L, 96, 96, 480, 220L, 40.7),
                new ExecutionTimelineResponse.StepTrend("s1", "Connexion", "POST", "STABLE", 250L, 268L, 96, 96, 480, 18L, 7.2),
                new ExecutionTimelineResponse.StepTrend("s2", "Consulter le solde", "GET", "STABLE", 185L, 196L, 96, 96, 480, 11L, 5.9)));
        ExecutionTimelineResponse.ResourceSeries resources = new ExecutionTimelineResponse.ResourceSeries("PROMETHEUS", 10, "process_resident_memory_bytes",
                null, 30, cpu, mem);
        List<ExecutionTimelineResponse.Insight> insights = List.of(
                new ExecutionTimelineResponse.Insight("DEGRADATION", "Le P95 de « Virement national » a doublé entre le début et la fin du test."),
                new ExecutionTimelineResponse.Insight("ERRORS", "Les erreurs apparaissent après 1 min de test, surtout sur « Virement national » (HTTP 500)."),
                new ExecutionTimelineResponse.Insight("LOAD", "Le débit plafonne à 8 req/s à partir de 20 utilisateurs actifs."));
        return new ExecutionTimelineResponse("RESULTS", true, 5, 312_000L, t, vus, requests, throughput, avg, p95, errors, paused, List.of(),
                List.of("HTTP 500", "HTTP 409", "Délai dépassé"), List.of(), List.of(new ExecutionTimelineResponse.PauseRange(150, 162)),
                insights, evolution, resources, START.toEpochMilli(), new ExecutionTimelineResponse.ExtremeMarker(3, 18, 15),
                new ExecutionTimelineResponse.ExtremeMarker(48, 2150, 241), null);
    }
}
