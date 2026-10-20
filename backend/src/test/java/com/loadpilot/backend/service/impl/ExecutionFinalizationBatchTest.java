package com.loadpilot.backend.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ExecutionRequestDetail;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.entity.Scenario;
import com.loadpilot.backend.entity.Step;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.ScenarioStatus;
import com.loadpilot.backend.enums.StepStatus;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionRequestDetailRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.StepRepository;
import com.loadpilot.backend.service.execution.RequestDetail;
import com.loadpilot.backend.service.execution.ScenarioExecutionOutcome;
import com.loadpilot.backend.service.execution.StepOutcome;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ecriture des resultats en fin d'execution (ExecutionTransactionHelper.finalizeExecution) avec un
 * volume significatif : contenu identique ligne par ligne a ce que le moteur a produit, et insertions
 * regroupees par lots JDBC (hibernate.jdbc.batch_size + order_inserts, application.yml) au lieu d'un
 * aller-retour SQL par ligne.
 *
 * Mesure : allers-retours SQL et ordres SQL prepares (JDBC), du seul thread du test, pendant la
 * finalisation - jamais ceux des taches planifiees qui tournent en meme temps. Sans lots,
 * chaque insertion prepare son propre ordre (au moins une par ligne) ; avec des lots de 50, un ordre
 * par lot et par table.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionFinalizationBatchTest {

    private static final int RESULTS = 3000;
    /** Une requete sur 10 a un detail (echec ou echantillon) : insertions alternees entre deux tables. */
    private static final int DETAIL_EVERY = 10;

    @Autowired private ExecutionTransactionHelper transactionHelper;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private ExecutionStepResultRepository resultRepository;
    @Autowired private ExecutionRequestDetailRepository detailRepository;
    @Autowired private ScenarioRepository scenarioRepository;
    @Autowired private StepRepository stepRepository;
    @Autowired private ApplicationRepository applicationRepository;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @MockBean private JwtDecoder jwtDecoder;

    /** Allers-retours reels vers la base : chaque execute / executeUpdate / executeQuery / executeBatch. */
    static final AtomicLong ROUND_TRIPS = new AtomicLong();
    /** Ordres SQL prepares (Connection.prepareStatement). */
    static final AtomicLong PREPARED = new AtomicLong();
    /** Allers-retours d'AUTRES threads pendant la mesure (taches planifiees...) : affiches, jamais comptes. */
    static final AtomicLong OTHER_THREADS_ROUND_TRIPS = new AtomicLong();
    /**
     * Seul thread mesure : celui du test, qui appelle finalizeExecution (transaction synchrone, tout son
     * SQL passe par ce thread). null = aucune mesure en cours. Sans ce filtre, les taches planifiees du
     * contexte (ScheduledExecutionPoller toutes les 5 s, PausedExecutionWatchdog...) qui interrogent la
     * base pendant la finalisation etaient comptees aussi : echec intermittent sur une machine chargee.
     */
    static volatile Thread measuredThread;

    /**
     * Enveloppe la DataSource de ce contexte de test : compte les executions JDBC reellement envoyees
     * (un lot executeBatch = un seul aller-retour, quel que soit le nombre de lignes).
     */
    @TestConfiguration
    static class RoundTripCounting {
        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource dataSource && !(bean instanceof CountingMarker)
                            ? proxy(DataSource.class, dataSource, (m, r) -> m.getName().equals("getConnection")
                                    ? proxy(Connection.class, (Connection) r, ExecutionFinalizationBatchTest::wrapStatement) : r)
                            : bean;
                }
            };
        }
    }

    interface CountingMarker { }

    private static Object wrapStatement(Method method, Object result) {
        return result instanceof PreparedStatement statement && method.getName().equals("prepareStatement")
                ? proxy(PreparedStatement.class, statement, (m, r) -> r) : result;
    }

    /** Appel JDBC du thread mesure ? Les autres threads sont comptes a part (diagnostic). */
    private static boolean measured() {
        Thread thread = measuredThread;
        if (thread == null) return false;
        if (Thread.currentThread() == thread) return true;
        OTHER_THREADS_ROUND_TRIPS.incrementAndGet();
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, java.util.function.BiFunction<Method, Object, Object> after) {
        InvocationHandler handler = (p, method, args) -> {
            if (type == PreparedStatement.class && method.getName().startsWith("execute") && measured()) ROUND_TRIPS.incrementAndGet();
            if (type == Connection.class && method.getName().equals("prepareStatement") && measuredThread == Thread.currentThread()) {
                PREPARED.incrementAndGet();
            }
            try {
                return after.apply(method, method.invoke(target, args));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (T) Proxy.newProxyInstance(ExecutionFinalizationBatchTest.class.getClassLoader(),
                new Class<?>[] {type, CountingMarker.class}, handler);
    }

    @Test
    void thousandsOfResults_areWrittenIdentically_inBatches() {
        AppUser user = appUserRepository.saveAndFlush(AppUser.builder()
                .keycloakSubject(UUID.randomUUID().toString()).username("batch-user-" + UUID.randomUUID()).enabled(true).build());
        Application app = applicationRepository.saveAndFlush(Application.builder()
                .name("batch-app-" + UUID.randomUUID()).url("http://localhost:1").createdBy(user).build());
        Scenario scenario = scenarioRepository.saveAndFlush(Scenario.builder()
                .application(app).name("batch-scenario-" + UUID.randomUUID()).status(ScenarioStatus.ACTIVE)
                .createdBy(user).virtualUsers(10).rampUpSeconds(0).thinkTimeMs(0).build());
        Step login = stepRepository.saveAndFlush(Step.builder().scenario(scenario).name("Connexion").method(HttpMethod.POST)
                .url("/login").order(1).status(StepStatus.ACTIVE).build());
        Step balance = stepRepository.saveAndFlush(Step.builder().scenario(scenario).name("Solde").method(HttpMethod.GET)
                .url("/balance").order(2).status(StepStatus.ACTIVE).build());
        Execution execution = executionRepository.saveAndFlush(Execution.builder()
                .scenario(scenario).startedAt(Instant.now()).status(ExecutionStatus.RUNNING)
                .virtualUsers(10).rampUpSeconds(0).totalSteps(2).successfulSteps(0).failedSteps(0)
                // Seuil copie au lancement, comme une execution reelle : ~14 % d'erreurs > 1 % -> « Seuils dépassés ».
                .maxErrorRatePercent(1.0).build());

        // Resultats tels que le moteur les produit : 2 etapes alternees, 10 VUs, echecs reguliers, details.
        Instant start = Instant.parse("2026-10-16T08:00:00Z");
        List<StepOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < RESULTS; i++) {
            Step step = i % 2 == 0 ? login : balance;
            boolean success = i % 7 != 0;
            StepOutcome outcome = new StepOutcome(step.getId(), success ? 200 : 500, 50 + i % 300, success,
                    success ? null : "Reponse HTTP 500 (attendu 2xx-3xx)", start.plusMillis(i), i % 10, i / 20,
                    "http://localhost:1" + step.getUrl() + "?n=" + i);
            if (i % DETAIL_EVERY == 0) {
                outcome = outcome.withDetail(new RequestDetail(success ? RequestDetail.Kind.SAMPLE : RequestDetail.Kind.FAILURE,
                        step.getMethod().name(), outcome.requestUrl(), Map.of("Accept", "application/json"), null, false,
                        outcome.httpStatus(), Map.of("Content-Type", "application/json"), "{\"n\":" + i + "}", false, 8L, null));
            }
            outcomes.add(outcome);
        }

        ROUND_TRIPS.set(0);
        PREPARED.set(0);
        OTHER_THREADS_ROUND_TRIPS.set(0);
        measuredThread = Thread.currentThread();
        Execution finalized;
        try {
            finalized = transactionHelper.finalizeExecution(execution.getId(),
                    new ScenarioExecutionOutcome(outcomes, ExecutionStatus.FAILED, "Reponse HTTP 500"), false);
        } finally {
            measuredThread = null;
        }
        long roundTrips = ROUND_TRIPS.get();
        long preparedStatements = PREPARED.get();
        System.out.println("[ExecutionFinalizationBatchTest] " + (RESULTS + RESULTS / DETAIL_EVERY) + " insertions -> "
                + roundTrips + " allers-retours SQL, " + preparedStatements + " ordres SQL prepares (thread du test) ; "
                + OTHER_THREADS_ROUND_TRIPS.get() + " allers-retours d'autres threads pendant la mesure, non comptes");

        // ---- resultat final identique : statut, compteurs, chaque ligne, chaque detail.
        long failures = outcomes.stream().filter(o -> !o.success()).count();
        assertThat(finalized.getStatus()).isEqualTo(ExecutionStatus.THRESHOLDS_EXCEEDED);
        assertThat(finalized.getSuccessfulSteps()).isEqualTo(RESULTS - failures);
        assertThat(finalized.getFailedSteps()).isEqualTo(failures);

        transactionTemplate.executeWithoutResult(tx -> {
            List<ExecutionStepResult> saved = resultRepository.findByExecutionIdOrderByTimestampAsc(execution.getId());
            assertThat(saved).hasSize(RESULTS);
            for (int i = 0; i < RESULTS; i++) {
                StepOutcome expected = outcomes.get(i);
                ExecutionStepResult row = saved.get(i);
                Step step = i % 2 == 0 ? login : balance;
                assertThat(row.getStep().getId()).isEqualTo(step.getId());
                assertThat(row.getStepName()).isEqualTo(step.getName());
                assertThat(row.getStepMethod()).isEqualTo(step.getMethod());
                assertThat(row.getStepUrl()).isEqualTo(step.getUrl());
                assertThat(row.getHttpStatus()).isEqualTo(expected.httpStatus());
                assertThat(row.getResponseTime()).isEqualTo(expected.responseTimeMs());
                assertThat(row.getSuccess()).isEqualTo(expected.success());
                assertThat(row.getError()).isEqualTo(expected.error());
                assertThat(row.getTimestamp()).isEqualTo(expected.timestamp());
                assertThat(row.getVirtualUser()).isEqualTo(expected.virtualUser());
                assertThat(row.getIteration()).isEqualTo(expected.iteration());
                assertThat(row.getRequestUrl()).isEqualTo(expected.requestUrl());
                if (expected.detail() != null) {
                    // Chaque detail est rattache a SON resultat (cle etrangere), jamais a un voisin.
                    ExecutionRequestDetail detail = detailRepository.findByResultId(row.getId()).orElseThrow();
                    assertThat(detail.getRequestUrl()).isEqualTo(expected.requestUrl());
                    assertThat(detail.getResponseStatus()).isEqualTo(expected.httpStatus());
                    assertThat(detail.getKind()).isEqualTo(expected.detail().kind().name());
                    assertThat(detail.getResponseBody()).isEqualTo(expected.detail().responseBody());
                }
            }
        });
        assertThat(detailRepository.countByExecutionId(execution.getId())).isEqualTo(RESULTS / DETAIL_EVERY);

        // ---- insertions regroupees : (3000 + 300) / 50 = 66 lots + verrou, etapes, mise a jour de l'execution,
        // au lieu d'un aller-retour par ligne (3303 mesures avant les lots).
        assertThat(roundTrips).as("allers-retours SQL pendant la finalisation").isLessThanOrEqualTo(80);
        assertThat(preparedStatements).as("ordres SQL prepares pendant la finalisation").isLessThan(150);
    }
}
