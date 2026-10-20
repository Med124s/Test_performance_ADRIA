package com.loadpilot.backend.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.loadpilot.backend.dto.response.MetricResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Metric;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.StepStatus;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.MetricRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.report.ThroughputCalculator;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * EXACTITUDE des agregats SQL face a l'ancien calcul par la table metric.
 *
 * Sur un meme jeu de resultats (executions avec et sans pauses, echecs, requetes sans reponse, duree
 * absente, une execution a cheval sur la plage, une execution encore en cours) :
 *   - ANCIEN : les lignes metric sont generees EXACTEMENT comme le faisait MetricGenerationService
 *     (supprime - sa logique est reproduite ici telle quelle), puis moyennees par les requetes JPQL
 *     de l'ancien MetricRepository (copiees mot pour mot) ;
 *   - NOUVEAU : PerformanceAggregates et les lignes de /api/metrics (MetricServiceImpl), sur
 *     execution_step_result seulement.
 * Les deux doivent donner les memes moyennes (a l'arrondi flottant pres, 1e-9) et les memes lignes.
 *
 * Instants de l'an 2001 et application propre a ce test : aucune autre classe ne les utilise (base H2
 * partagee entre classes de test).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// SecretCipher : colonnes chiffrees de Application (sinon dependant de l'ordre des classes de test).
@Import({PerformanceAggregates.class, com.loadpilot.backend.security.SecretCipher.class})
class MetricEquivalenceTest {

    private static final Instant T0 = Instant.parse("2001-03-01T10:00:00Z");

    @Autowired private AppUserRepository appUserRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private ScenarioRepository scenarioRepository;
    @Autowired private StepRepository stepRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private ExecutionStepResultRepository resultRepository;
    @Autowired private MetricRepository metricRepository;
    @Autowired private PerformanceAggregates aggregates;
    @Autowired private EntityManager entityManager;

    private Application application;
    private Scenario scenarioA;
    private Scenario scenarioB;
    private final List<Execution> finished = new ArrayList<>();
    private final Map<UUID, List<ExecutionStepResult>> resultsByExecution = new HashMap<>();

    @BeforeEach
    void dataset() {
        AppUser user = appUserRepository.save(AppUser.builder().keycloakSubject(UUID.randomUUID().toString())
                .username("equivalence-" + UUID.randomUUID()).enabled(true).build());
        application = applicationRepository.save(Application.builder().name("equivalence-" + UUID.randomUUID())
                .url("http://localhost:1").createdBy(user).build());
        scenarioA = scenarioRepository.save(Scenario.builder().application(application).name("A-" + UUID.randomUUID())
                .status(ScenarioStatus.ACTIVE).createdBy(user).build());
        scenarioB = scenarioRepository.save(Scenario.builder().application(application).name("B-" + UUID.randomUUID())
                .status(ScenarioStatus.ACTIVE).createdBy(user).build());
        Step login = step(scenarioA, "Connexion", 1);
        Step balance = step(scenarioA, "Solde", 2);
        Step search = step(scenarioB, "Recherche", 1);

        // A : 7 requetes, toutes reussies, sans pause.
        execution(scenarioA, ExecutionStatus.SUCCESS, 3_500L, null, T0, List.of(login, balance),
                new long[] {120, 80, 95, 210, 64, 77, 133}, new boolean[] {true, true, true, true, true, true, true});
        // B : 13 requetes, 4 echecs (dont une sans reponse), 2 s de pause, a cheval sur la plage partielle.
        execution(scenarioA, ExecutionStatus.THRESHOLDS_EXCEEDED, 9_000L, 2_000L, T0.plusSeconds(3_550), List.of(login, balance),
                new long[] {300, 410, 250, 990, 120, 175, 260, 330, 15, 401, 222, 333, 444},
                new boolean[] {true, false, true, true, false, true, true, true, false, true, true, false, true});
        // C : annulee sans duree mesurable (debit absent, taux d'erreurs present).
        execution(scenarioB, ExecutionStatus.CANCELLED, null, null, T0.plusSeconds(7_200), List.of(search),
                new long[] {50, 60, 70, 80, 90}, new boolean[] {true, true, true, false, true});
        // D : echouee (redemarrage du serveur), toutes en echec.
        execution(scenarioB, ExecutionStatus.FAILED, 1_234L, null, T0.plusSeconds(9_000), List.of(search),
                new long[] {1_000, 2_000, 3_000}, new boolean[] {false, false, false});
        // E : encore en cours - aucune metric n'existait pour elle (generees a la fin), ignoree aussi par le nouveau calcul.
        Execution running = executionRepository.save(Execution.builder().scenario(scenarioB).startedAt(T0).status(ExecutionStatus.RUNNING)
                .totalSteps(1).successfulSteps(0).failedSteps(0).build());
        for (int i = 0; i < 4; i++) result(running, search, 5_000 + i, true, T0.plusSeconds(9_500 + i));

        // ANCIEN calcul : generation des metric, comme MetricGenerationService (executions terminees).
        for (Execution e : finished) generateOldMetrics(e, resultsByExecution.get(e.getId()));
        entityManager.flush();
        entityManager.clear();
    }

    private Step step(Scenario s, String name, int order) {
        return stepRepository.save(Step.builder().scenario(s).name(name).method(HttpMethod.GET).url("/" + name).order(order)
                .status(StepStatus.ACTIVE).build());
    }

    private void execution(Scenario s, ExecutionStatus status, Long duration, Long paused, Instant start, List<Step> steps,
            long[] times, boolean[] success) {
        int failed = 0;
        for (boolean ok : success) if (!ok) failed++;
        Execution e = executionRepository.save(Execution.builder().scenario(s).startedAt(start).status(status)
                .totalSteps(steps.size()).successfulSteps(times.length - failed).failedSteps(failed)
                .duration(duration).pausedDurationMs(paused == null ? 0L : paused).build());
        finished.add(e);
        for (int i = 0; i < times.length; i++) {
            result(e, steps.get(i % steps.size()), times[i], success[i], start.plusMillis(i * 700L));
        }
    }

    private void result(Execution e, Step st, long time, boolean ok, Instant at) {
        // Une requete sans reponse (echec technique) n'a pas de code HTTP.
        Integer status = ok ? Integer.valueOf(200) : time == 15 ? null : Integer.valueOf(500);
        ExecutionStepResult r = resultRepository.save(ExecutionStepResult.builder().execution(e).step(st).stepName(st.getName())
                .stepMethod(st.getMethod()).stepUrl(st.getUrl()).httpStatus(status).responseTime(time).success(ok).timestamp(at).build());
        resultsByExecution.computeIfAbsent(e.getId(), k -> new ArrayList<>()).add(r);
    }

    /** Logique de l'ancien MetricGenerationService.generateForExecution, reproduite telle quelle. */
    private void generateOldMetrics(Execution execution, List<ExecutionStepResult> results) {
        BigDecimal throughput = ThroughputCalculator.compute(results.size(), execution.activeDurationMs());
        BigDecimal errorRate = results.isEmpty() ? null : BigDecimal.valueOf(execution.getFailedSteps())
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(results.size()), 2, RoundingMode.HALF_UP);
        for (ExecutionStepResult result : results) {
            metricRepository.save(Metric.builder().application(execution.getScenario().getApplication())
                    .scenario(execution.getScenario()).step(result.getStep()).execution(execution)
                    .responseTime(result.getResponseTime() != null ? result.getResponseTime().intValue() : null)
                    .statusCode(result.getHttpStatus()).throughput(throughput).errorRate(errorRate)
                    .timestamp(result.getTimestamp()).build());
        }
    }

    // ---- Requetes de l'ANCIEN MetricRepository, copiees mot pour mot. ----

    private Double oldAverage(String column, String where, Map<String, Object> params) {
        var query = entityManager.createQuery("SELECT AVG(m." + column + ") FROM Metric m WHERE " + where, Double.class);
        params.forEach(query::setParameter);
        return query.getSingleResult();
    }

    private PerformanceAggregates.Averages oldBetween(Instant from, Instant to) {
        Map<String, Object> p = Map.of("from", from, "to", to);
        String where = "m.timestamp BETWEEN :from AND :to";
        return new PerformanceAggregates.Averages(oldAverage("responseTime", where, p), oldAverage("throughput", where, p),
                oldAverage("errorRate", where, p));
    }

    private PerformanceAggregates.Averages oldForApplication(UUID applicationId) {
        Map<String, Object> p = Map.of("applicationId", applicationId);
        String where = "m.application.id = :applicationId";
        return new PerformanceAggregates.Averages(oldAverage("responseTime", where, p), oldAverage("throughput", where, p),
                oldAverage("errorRate", where, p));
    }

    private static void assertSame(PerformanceAggregates.Averages actual, PerformanceAggregates.Averages expected) {
        assertThat(expected.responseTime()).as("jeu de donnees non vide").isNotNull();
        assertThat(actual.responseTime()).as("temps de reponse moyen").isCloseTo(expected.responseTime(), within(1e-9));
        assertThat(actual.throughput()).as("debit moyen").isCloseTo(expected.throughput(), within(1e-9));
        assertThat(actual.errorRate()).as("taux d'erreurs moyen").isCloseTo(expected.errorRate(), within(1e-9));
        // Valeurs affichees par le Dashboard (2, 3 et 2 decimales) : identiques.
        assertThat(round(actual.responseTime(), 2)).isEqualTo(round(expected.responseTime(), 2));
        assertThat(round(actual.throughput(), 3)).isEqualTo(round(expected.throughput(), 3));
        assertThat(round(actual.errorRate(), 2)).isEqualTo(round(expected.errorRate(), 2));
    }

    private static BigDecimal round(Double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    @Test
    void overTheWholeRange_sameAveragesAsTheOldMetricTable() {
        Instant from = T0.minusSeconds(60);
        Instant to = T0.plusSeconds(20_000);
        assertSame(aggregates.between(from, to), oldBetween(from, to));
    }

    @Test
    void overAPartialRange_cuttingAnExecutionInTwo_sameAverages() {
        // Coupe l'execution B (requetes de T0+3550 s a T0+3558,4 s) apres sa 6e requete.
        Instant from = T0.minusSeconds(60);
        Instant to = T0.plusSeconds(3_550).plusMillis(5 * 700L + 100);
        PerformanceAggregates.Averages expected = oldBetween(from, to);
        assertSame(aggregates.between(from, to), expected);
        assertThat(expected.responseTime()).as("seulement une partie des requetes de B")
                .isNotEqualTo(oldBetween(T0.minusSeconds(60), T0.plusSeconds(20_000)).responseTime());
    }

    @Test
    void forTheApplication_sameAverages() {
        assertSame(aggregates.forApplication(application.getId()), oldForApplication(application.getId()));
    }

    @Test
    void perScenario_sameAverageResponseTime() {
        Instant from = T0.minusSeconds(60);
        Instant to = T0.plusSeconds(20_000);
        // Ancienne requete groupee (MetricRepository.averageResponseTimeByScenarioIn), copiee mot pour mot.
        List<Object[]> old = entityManager.createQuery("SELECT m.scenario.id AS scenarioId, AVG(m.responseTime) AS averageResponseTime "
                        + "FROM Metric m WHERE m.scenario.id IN :scenarioIds AND m.timestamp BETWEEN :from AND :to GROUP BY m.scenario.id",
                        Object[].class)
                .setParameter("scenarioIds", List.of(scenarioA.getId(), scenarioB.getId())).setParameter("from", from).setParameter("to", to)
                .getResultList();
        Map<UUID, Double> expected = new HashMap<>();
        for (Object[] row : old) expected.put((UUID) row[0], ((Number) row[1]).doubleValue());

        Map<UUID, Double> actual = new HashMap<>();
        for (var row : resultRepository.averageResponseTimeByScenarioIn(PerformanceAggregates.ACTIVE,
                List.of(scenarioA.getId(), scenarioB.getId()), from, to)) {
            actual.put(row.getScenarioId(), row.getAverageResponseTime());
        }
        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((id, avg) -> assertThat(actual.get(id)).isCloseTo(avg, within(1e-9)));
    }

    @Test
    void apiMetricsRows_sameValuesAsTheOldMetricRows() {
        for (Execution e : finished) {
            List<Metric> old = entityManager.createQuery("SELECT m FROM Metric m WHERE m.execution.id = :id ORDER BY m.timestamp",
                    Metric.class).setParameter("id", e.getId()).getResultList();
            List<MetricResponse> rows = resultRepository.searchMetricRows(PerformanceAggregates.ACTIVE, null, null, null, e.getId())
                    .stream().map(MetricServiceImpl::toResponse).toList();
            assertThat(rows).hasSameSizeAs(old);
            for (int i = 0; i < old.size(); i++) {
                Metric expected = old.get(i);
                MetricResponse actual = rows.get(i);
                assertThat(actual.responseTime()).isEqualTo(expected.getResponseTime());
                assertThat(actual.statusCode()).isEqualTo(expected.getStatusCode());
                assertThat(actual.timestamp()).isEqualTo(expected.getTimestamp());
                assertThat(actual.executionId()).isEqualTo(expected.getExecution().getId().toString());
                assertThat(actual.scenarioId()).isEqualTo(expected.getScenario().getId().toString());
                assertThat(actual.applicationId()).isEqualTo(expected.getApplication().getId().toString());
                assertThat(actual.stepId()).isEqualTo(expected.getStep().getId().toString());
                if (expected.getThroughput() == null) assertThat(actual.throughput()).isNull();
                else assertThat(actual.throughput()).isEqualByComparingTo(expected.getThroughput());
                assertThat(actual.errorRate()).isEqualByComparingTo(expected.getErrorRate());
            }
        }
        // Meme nombre de lignes pour l'application : 28 requetes terminees, aucune pour l'execution en cours.
        long oldRows = entityManager.createQuery("SELECT COUNT(m) FROM Metric m WHERE m.application.id = :id", Long.class)
                .setParameter("id", application.getId()).getSingleResult();
        assertThat(oldRows).isEqualTo(28);
        assertThat(resultRepository.countMetricRows(PerformanceAggregates.ACTIVE, application.getId(), null, null, null)).isEqualTo(oldRows);
    }
}
