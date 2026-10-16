package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.DashboardOverviewResponse;
import com.loadpilot.backend.dto.response.DashboardOverviewResponse.ExecutionBrief;
import com.loadpilot.backend.dto.response.DashboardOverviewResponse.Kpis;
import com.loadpilot.backend.dto.response.DashboardOverviewResponse.ScenarioOption;
import com.loadpilot.backend.dto.response.DashboardOverviewResponse.ScenarioRow;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.dto.response.DashboardOverviewResponse.ScheduledBrief;
import com.loadpilot.backend.dto.response.DashboardOverviewResponse.SlowStep;
import com.loadpilot.backend.dto.response.DashboardOverviewResponse.StatusCounts;
import com.loadpilot.backend.dto.response.DashboardOverviewResponse.WatchItem;
import com.loadpilot.backend.dto.response.ScenarioTrendResponse;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.entity.ScheduledExecution;
import com.loadpilot.backend.enums.ApplicationStatus;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.MonitoringType;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nouveau Dashboard : tout est calcule cote serveur, filtre par application et par periode
 * (executions LANCEES entre from et to, bornes incluses ; sans borne = tout l'historique).
 *
 * Performance : comptages et sommes en SQL ; P95 de la periode calcule par la base
 * (PERCENTILE_DISC = methode du « rang le plus proche » des rapports, aucune requete chargee en
 * memoire) ; P95 / debit / P95 par etape de chaque execution lus dans son resume enregistre
 * (ExecutionStatsService, calcule une seule fois pour une execution terminee).
 *
 * Comparaison : derniere execution de chaque scenario dans la periode vs l'execution precedente
 * (reussie ou echouee) du meme scenario, meme anterieure a la periode (seuils : DashboardRules).
 */
@Service
@RequiredArgsConstructor
public class DashboardOverviewService {

    static final int RECENT_LIMIT = 5;
    static final int UPCOMING_LIMIT = 3;
    static final int SLOWEST_STEPS_LIMIT = 5;
    static final int TREND_LIMIT = 200;
    /** Tableau « Derniere execution par scenario » : 10 lignes par page par defaut (interface), 100 au plus. */
    static final int MAX_PAGE_SIZE = 100;
    private static final List<ExecutionStatus> COMPARABLE = List.of(ExecutionStatus.SUCCESS, ExecutionStatus.THRESHOLDS_EXCEEDED, ExecutionStatus.FAILED);
    private static final List<ExecutionStatus> ACTIVE = List.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING, ExecutionStatus.PAUSED);

    private final EntityManager entityManager;
    private final ExecutionStatsService statsService;
    private final com.loadpilot.backend.service.AppSettingsService settings;

    /** P95 de periode deja calcules (cle : filtre + nombre d'executions + derniere fin) - petite LRU. */
    private final Map<String, Long> periodP95Cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 50;
        }
    });

    /** Filtre commun : application (optionnelle) et periode sur startedAt. */
    private record Filter(UUID applicationId, Instant from, Instant to) {
        String where(String alias) {
            return alias + ".startedAt >= :from and " + alias + ".startedAt <= :to"
                    + (applicationId != null ? " and " + alias + ".scenario.application.id = :app" : "");
        }

        <T> TypedQuery<T> bind(TypedQuery<T> q) {
            q.setParameter("from", from).setParameter("to", to);
            if (applicationId != null) q.setParameter("app", applicationId);
            return q;
        }
    }

    @Transactional(readOnly = true)
    public DashboardOverviewResponse overview(UUID applicationId, Instant from, Instant to) {
        Filter f = new Filter(applicationId, from != null ? from : Instant.EPOCH, to != null ? to : Instant.now());

        // ---- comptages par statut, requetes envoyees et en echec (sommes SQL)
        Map<ExecutionStatus, long[]> byStatus = new EnumMap<>(ExecutionStatus.class);
        for (Object[] row : f.bind(entityManager.createQuery("select e.status, count(e), coalesce(sum(e.successfulSteps), 0), "
                + "coalesce(sum(e.failedSteps), 0) from Execution e where " + f.where("e") + " group by e.status", Object[].class))
                .getResultList()) {
            byStatus.put((ExecutionStatus) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue()});
        }
        long executions = 0;
        long requests = 0;
        long failedRequests = 0;
        for (long[] v : byStatus.values()) {
            executions += v[0];
            requests += v[1] + v[2];
            failedRequests += v[2];
        }
        long running = ACTIVE.stream().mapToLong(s -> byStatus.getOrDefault(s, new long[3])[0]).sum();
        StatusCounts statusCounts = new StatusCounts(count(byStatus, ExecutionStatus.SUCCESS), count(byStatus, ExecutionStatus.FAILED),
                count(byStatus, ExecutionStatus.CANCELLED), running, count(byStatus, ExecutionStatus.THRESHOLDS_EXCEEDED));
        Kpis kpis = new Kpis(periodP95(f, executions), rate(failedRequests, requests), requests, executions, running);

        // ---- derniere execution par scenario (resume calcule si besoin) et comparaison : pour les points a
        // surveiller et les etapes les plus lentes (tous les scenarios). Le tableau, lui, est pagine
        // (latestByScenario ci-dessous) ; ici seulement la liste des scenarios pour « Evolution ».
        List<Execution> latest = latestExecutions(f, null, null);
        List<ScenarioRow> rows = new ArrayList<>();
        Map<String, Execution> latestById = new LinkedHashMap<>();
        for (Execution e : latest) {
            rows.add(toRow(e));
            latestById.put(e.getId().toString(), e);
        }
        List<ScenarioOption> scenarios = rows.stream().map(r -> new ScenarioOption(r.scenarioId(), r.scenarioName())).toList();

        return new DashboardOverviewResponse(applicationId == null ? null : applicationId.toString(), from, to, kpis, statusCounts,
                watchItems(f, rows, latest), scenarios, slowestSteps(latestById, rows), active(f), recent(f), upcoming(applicationId));
    }

    /**
     * Tableau « Derniere execution par scenario », pagine cote serveur : une ligne par scenario execute
     * dans la periode (sa derniere execution), de la plus recente a la plus ancienne. Seules les lignes de
     * la page sont calculees (comparaison avec l'execution precedente, resume).
     */
    @Transactional(readOnly = true)
    public PagedResponse<ScenarioRow> latestByScenario(UUID applicationId, Instant from, Instant to, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Filter f = new Filter(applicationId, from != null ? from : Instant.EPOCH, to != null ? to : Instant.now());
        long total = f.bind(entityManager.createQuery("select count(distinct e.scenario.id) from Execution e where " + f.where("e"),
                Long.class)).getSingleResult();
        List<ScenarioRow> rows = latestExecutions(f, safePage * safeSize, safeSize).stream().map(this::toRow).toList();
        return new PagedResponse<>(rows, safePage, safeSize, total, (int) Math.ceil(total / (double) safeSize));
    }

    /** Derniere execution de chaque scenario dans la periode, la plus recente d'abord (id : ordre stable). */
    private List<Execution> latestExecutions(Filter f, Integer first, Integer max) {
        TypedQuery<Execution> q = f.bind(entityManager.createQuery("select e from Execution e join fetch e.scenario s "
                + "join fetch s.application where " + f.where("e") + " and e.startedAt = (select max(e2.startedAt) from Execution e2 "
                + "where e2.scenario = e.scenario and e2.startedAt >= :from and e2.startedAt <= :to) order by e.startedAt desc, e.id desc",
                Execution.class));
        if (first != null) q.setFirstResult(first).setMaxResults(max);
        return q.getResultList();
    }

    /** Ligne du tableau : derniere execution comparee a l'execution precedente du meme scenario. */
    private ScenarioRow toRow(Execution e) {
        ensureStats(e);
        Execution previous = previousComparable(e);
        Long prevP95 = previous == null ? null : previous.getP95Ms();
        Double change = e.getStatus().isActive() ? null : DashboardRules.changePercent(e.getP95Ms(), prevP95);
        return new ScenarioRow(e.getId().toString(), e.getScenario().getId().toString(), e.getScenario().getName(),
                e.getScenario().getApplication().getId().toString(), e.getScenario().getApplication().getName(), e.getStartedAt(),
                e.getStatus(), e.getVirtualUsers(), e.getP95Ms(), errorRate(e), e.getThroughputRps(), e.getCpuMaxPercent(),
                previous == null ? null : previous.getId().toString(), prevP95, change, DashboardRules.trend(change, settings.regressionPercent()));
    }

    @Transactional(readOnly = true)
    public ScenarioTrendResponse scenarioTrend(UUID scenarioId, Instant from, Instant to) {
        return scenarioTrend(scenarioId, from, to, null);
    }

    /**
     * « Évolution des performances » : executions terminees (reussies / echouees) du scenario dans la
     * periode ; stepName (optionnel) : P95 et taux d'erreurs de cette etape dans chaque execution
     * (resume enregistre, meme calcul que le tableau des etapes du rapport).
     */
    @Transactional(readOnly = true)
    public ScenarioTrendResponse scenarioTrend(UUID scenarioId, Instant from, Instant to, String stepName) {
        com.loadpilot.backend.entity.Scenario scenario = entityManager.find(com.loadpilot.backend.entity.Scenario.class, scenarioId);
        if (scenario == null) throw new ResourceNotFoundException("Scenario introuvable : " + scenarioId);
        List<Execution> list = entityManager.createQuery("select e from Execution e where e.scenario.id = :sid and e.startedAt >= :from "
                        + "and e.startedAt <= :to and e.status in :st order by e.startedAt desc", Execution.class)
                .setParameter("sid", scenarioId).setParameter("from", from != null ? from : Instant.EPOCH)
                .setParameter("to", to != null ? to : Instant.now()).setParameter("st", COMPARABLE)
                .setMaxResults(TREND_LIMIT).getResultList();
        List<ScenarioTrendResponse.Point> points = new ArrayList<>();
        Map<String, ScenarioTrendResponse.StepOption> options = new LinkedHashMap<>();
        String wanted = stepName == null || stepName.isBlank() ? null : stepName.trim();
        for (Execution e : list) {
            ensureStats(e);
            ExecutionStatsService.StepStat step = null;
            for (ExecutionStatsService.StepStat s : statsService.stepStats(e)) {
                options.putIfAbsent(s.name(), new ScenarioTrendResponse.StepOption(s.name(), s.method()));
                if (s.name().equals(wanted) && step == null) step = s;
            }
            points.add(new ScenarioTrendResponse.Point(e.getId().toString(), e.getStartedAt(), e.getStatus(), e.getP95Ms(), errorRate(e),
                    e.getThroughputRps(), e.getVirtualUsers(), step == null ? null : step.p95(),
                    step == null || step.failed() == null ? null : rate(step.failed(), step.total()), step == null ? null : step.total()));
        }
        Collections.reverse(points);
        // Etapes dans l'ordre de la plus recente execution (ordre du scenario).
        return new ScenarioTrendResponse(scenarioId.toString(), scenario.getName(), List.copyOf(options.values()), wanted, points);
    }

    // ------------------------------------------------------------------ blocs

    private List<WatchItem> watchItems(Filter f, List<ScenarioRow> rows, List<Execution> latest) {
        List<WatchItem> items = new ArrayList<>();
        // Applications en echec de connexion (etat actuel)
        TypedQuery<Application> apps = entityManager.createQuery("select a from Application a where a.archived = false "
                + "and a.status in :st" + (f.applicationId() != null ? " and a.id = :app" : "") + " order by a.name", Application.class)
                .setParameter("st", List.of(ApplicationStatus.FAILED, ApplicationStatus.ERROR));
        if (f.applicationId() != null) apps.setParameter("app", f.applicationId());
        for (Application a : apps.getResultList()) {
            items.add(new WatchItem("APPLICATION_FAILED", "danger", "Application « " + a.getName() + " » : connexion en échec",
                    a.getStatusMessage(), "/applications?q=" + encode(a.getName())));
        }
        for (ScenarioRow r : rows) {
            if (r.status() == ExecutionStatus.FAILED) {
                items.add(new WatchItem("LAST_EXECUTION_FAILED", "danger", "Scénario « " + r.scenarioName() + " » : dernière exécution échouée",
                        r.errorRate() == null ? null : "Taux d'erreurs " + oneDecimal(r.errorRate().doubleValue()) + " %",
                        "/executions/report/" + r.executionId()));
            }
            if (r.status() == ExecutionStatus.THRESHOLDS_EXCEEDED) {
                items.add(new WatchItem("THRESHOLDS_EXCEEDED", "warning", "Scénario « " + r.scenarioName() + " » : seuils de réussite dépassés",
                        r.errorRate() == null ? null : "Taux d'erreurs " + oneDecimal(r.errorRate().doubleValue()) + " %",
                        "/executions/report/" + r.executionId()));
            }
            if (DashboardRules.isRegression(r.p95ChangePercent(), settings.regressionPercent())) {
                items.add(new WatchItem("REGRESSION", "warning", "Scénario « " + r.scenarioName() + " » : P95 en hausse de "
                        + oneDecimal(r.p95ChangePercent()) + " %", "P95 " + oneDecimal(r.previousP95Ms()) + " ms → "
                        + oneDecimal(r.p95Ms()) + " ms par rapport à l'exécution précédente", "/executions/report/" + r.executionId()));
            }
        }
        // Monitoring configure mais muet lors de la derniere execution de l'application dans la periode
        Map<UUID, Execution> lastByApp = new LinkedHashMap<>();
        for (Execution e : latest) {
            Application a = e.getScenario().getApplication();
            if (a.getMonitoringType() == null || a.getMonitoringType() == MonitoringType.NONE || e.getStatus().isActive()) continue;
            lastByApp.merge(a.getId(), e, (x, y) -> x.getStartedAt().isAfter(y.getStartedAt()) ? x : y);
        }
        for (Execution e : lastByApp.values()) {
            if (e.getMonitoringMessage() == null) continue;
            Application a = e.getScenario().getApplication();
            items.add(new WatchItem("MONITORING_DOWN", "warning", "Application « " + a.getName() + " » : le monitoring CPU / RAM ne répond pas",
                    e.getMonitoringMessage(), "/applications?q=" + encode(a.getName())));
        }
        return items;
    }

    /** Les 5 etapes au P95 le plus eleve dans la derniere execution terminee de chaque scenario. */
    private List<SlowStep> slowestSteps(Map<String, Execution> latestById, List<ScenarioRow> rows) {
        List<SlowStep> all = new ArrayList<>();
        for (ScenarioRow r : rows) {
            Execution e = latestById.get(r.executionId());
            for (ExecutionStatsService.StepStat s : statsService.stepStats(e)) {
                if (s.p95() != null) all.add(new SlowStep(s.name(), s.method(), r.scenarioName(), r.executionId(), s.p95(), s.total()));
            }
        }
        all.sort(Comparator.comparing(SlowStep::p95Ms, Comparator.reverseOrder()));
        return all.size() > SLOWEST_STEPS_LIMIT ? List.copyOf(all.subList(0, SLOWEST_STEPS_LIMIT)) : all;
    }

    private List<ExecutionBrief> active(Filter f) {
        return briefs(f.bind(entityManager.createQuery("select e from Execution e join fetch e.scenario s join fetch s.application where "
                + f.where("e") + " and e.status in :st order by e.startedAt desc", Execution.class)).setParameter("st", ACTIVE)
                .getResultList());
    }

    private List<ExecutionBrief> recent(Filter f) {
        return briefs(f.bind(entityManager.createQuery("select e from Execution e join fetch e.scenario s join fetch s.application where "
                + f.where("e") + " order by e.startedAt desc", Execution.class)).setMaxResults(RECENT_LIMIT).getResultList());
    }

    /** Prochaines executions planifiees (dans le futur : la periode ne s'applique pas, l'application oui). */
    private List<ScheduledBrief> upcoming(UUID applicationId) {
        TypedQuery<ScheduledExecution> q = entityManager.createQuery("select s from ScheduledExecution s join fetch s.scenario sc "
                + "where s.enabled = true and s.pausedByScenario = false and s.nextRunAt >= :now"
                + (applicationId != null ? " and sc.application.id = :app" : "") + " order by s.nextRunAt asc", ScheduledExecution.class)
                .setParameter("now", Instant.now());
        if (applicationId != null) q.setParameter("app", applicationId);
        return q.setMaxResults(UPCOMING_LIMIT).getResultList().stream()
                .map(s -> new ScheduledBrief(s.getId().toString(), s.getName(), s.getScenario().getName(), s.getNextRunAt())).toList();
    }

    // ------------------------------------------------------------------ calculs

    /**
     * P95 de toutes les requetes des executions de la periode, calcule par la base
     * (PERCENTILE_DISC(0.95) = rang le plus proche, comme les rapports). Mis en cache tant que le
     * nombre d'executions et la derniere fin d'execution du filtre ne changent pas.
     */
    private Long periodP95(Filter f, long executions) {
        if (executions == 0) return null;
        Object[] signature = f.bind(entityManager.createQuery("select count(e), max(e.finishedAt) from Execution e where " + f.where("e")
                + " and e.status not in :st", Object[].class)).setParameter("st", ACTIVE).getSingleResult();
        long active = executions - ((Number) signature[0]).longValue();
        // Cache seulement si toutes les executions du filtre sont terminees (resultats definitifs).
        String key = active > 0 ? null : f.applicationId() + "|" + f.from() + "|" + (f.to().getEpochSecond() / 60) + "|"
                + signature[0] + "|" + signature[1];
        if (key != null && periodP95Cache.containsKey(key)) return periodP95Cache.get(key);
        jakarta.persistence.Query q = entityManager.createNativeQuery("select percentile_disc(0.95) within group (order by r.response_time_ms) "
                + "from execution_step_result r join execution e on e.id = r.execution_id"
                + (f.applicationId() != null ? " join scenario s on s.id = e.scenario_id" : "")
                + " where e.started_at >= :from and e.started_at <= :to"
                + (f.applicationId() != null ? " and s.application_id = :app" : ""))
                .setParameter("from", f.from()).setParameter("to", f.to());
        if (f.applicationId() != null) q.setParameter("app", f.applicationId());
        Object value = q.getSingleResult();
        Long p95 = value == null ? null : ((Number) value).longValue();
        if (key != null) periodP95Cache.put(key, p95);
        return p95;
    }

    /** Execution precedente comparable (reussie ou echouee) du meme scenario, toutes periodes confondues. */
    private Execution previousComparable(Execution e) {
        List<Execution> list = entityManager.createQuery("select e from Execution e where e.scenario.id = :sid and e.startedAt < :t "
                        + "and e.status in :st order by e.startedAt desc", Execution.class)
                .setParameter("sid", e.getScenario().getId()).setParameter("t", e.getStartedAt()).setParameter("st", COMPARABLE)
                .setMaxResults(1).getResultList();
        if (list.isEmpty()) return null;
        ensureStats(list.get(0));
        return list.get(0);
    }

    /** Resume absent (execution anterieure) : calcule une fois, enregistre, puis relu. */
    private void ensureStats(Execution e) {
        if (!ExecutionStatsService.needsComputation(e)) return;
        try {
            statsService.compute(e.getId());
            entityManager.refresh(e);
        } catch (RuntimeException ex) {
            // Resume indisponible : valeurs simplement absentes (jamais inventees).
        }
    }

    private List<ExecutionBrief> briefs(List<Execution> list) {
        return list.stream().map(e -> new ExecutionBrief(e.getId().toString(), e.getScenario().getName(),
                e.getScenario().getApplication().getName(), e.getStatus(), e.getStartedAt(), e.getDuration())).toList();
    }

    static BigDecimal errorRate(Execution e) {
        int ok = e.getSuccessfulSteps() == null ? 0 : e.getSuccessfulSteps();
        int ko = e.getFailedSteps() == null ? 0 : e.getFailedSteps();
        return rate(ko, ok + ko);
    }

    static BigDecimal rate(long part, long total) {
        if (total <= 0) return null;
        return BigDecimal.valueOf(part * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    }

    private static long count(Map<ExecutionStatus, long[]> byStatus, ExecutionStatus s) {
        return byStatus.containsKey(s) ? byStatus.get(s)[0] : 0;
    }

    private static String oneDecimal(Number v) {
        return DashboardRules.format(v);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
