package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.service.report.ThroughputCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Moyennes de performance du Dashboard (temps de reponse, debit, taux d'erreurs), calculees par
 * agregats SQL sur execution_step_result - plus aucune copie des resultats (ancienne table metric).
 *
 * MEMES VALEURS que l'ancien calcul, qui faisait la moyenne, sur les lignes metric (une par requete
 * d'une execution terminee), de :
 *   - responseTime : le temps de reponse de la requete ;
 *   - throughput : le debit de SON execution, ThroughputCalculator(requetes, duree active), 3 decimales ;
 *   - errorRate : le taux d'erreurs de SON execution, echecs x 100 / requetes, 2 decimales.
 * Le SQL regroupe par execution (nombre de requetes retenues, somme des temps) ; ce composant combine
 * ces groupes - quelques centaines ou milliers, jamais les resultats eux-memes - avec exactement ces
 * formules et ces arrondis : debit et taux d'erreurs sont ponderes par le nombre de requetes retenues,
 * comme l'etait chaque ligne metric. Executions en cours exclues (leurs metric n'existaient pas encore).
 */
@Component
@RequiredArgsConstructor
public class PerformanceAggregates {

    /** Statuts d'une execution pas encore terminee : ses resultats ne comptent pas (comme les anciennes metric). */
    public static final List<ExecutionStatus> ACTIVE = List.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING, ExecutionStatus.PAUSED);

    /** Moyennes ; chacune null s'il n'y a rien a moyenner (jamais 0 invente). */
    public record Averages(Double responseTime, Double throughput, Double errorRate) {
    }

    private final ExecutionStepResultRepository resultRepository;

    /** Nombre d'executions relues par requete (liste IN bornee, quel que soit le SGBD). */
    static final int IDS_PER_QUERY = 1000;

    /**
     * Toutes les requetes : sans plage de dates, chaque execution terminee compte pour toutes ses requetes
     * (reussies + echecs, compteurs relus en base) - aucun regroupement des resultats necessaire, un seul
     * parcours pour le temps de reponse.
     */
    public Averages overall() {
        return whole(resultRepository.responseTimeTotals(activeIds()), resultRepository.finishedExecutionTotals(ACTIVE));
    }

    public Averages forApplication(UUID applicationId) {
        return whole(resultRepository.responseTimeTotalsForApplication(ACTIVE, applicationId),
                resultRepository.finishedExecutionTotalsForApplication(ACTIVE, applicationId));
    }

    /** times : [somme, nombre] des temps ; executions : [id, reussies, echecs, duree, pause], poids = reussies + echecs. */
    private static Averages whole(List<Object[]> times, List<Object[]> executions) {
        List<Object[]> rows = new java.util.ArrayList<>(executions.size() + 1);
        for (Object[] e : executions) {
            long requests = (long) intValue(e[1]) + intValue(e[2]);
            rows.add(new Object[] {e[0], requests, null, 0L, e[1], e[2], e[3], e[4]});
        }
        Averages weighted = combine(rows);
        Object[] t = times.isEmpty() ? new Object[] {null, 0L} : times.get(0);
        long count = t[1] == null ? 0 : ((Number) t[1]).longValue();
        Double responseTime = count == 0 || t[0] == null ? null : ((Number) t[0]).doubleValue() / count;
        return new Averages(responseTime, weighted.throughput(), weighted.errorRate());
    }

    /** Requetes dont la date est dans [from, to] (une execution a cheval ne compte que pour ses requetes de la plage). */
    public Averages between(Instant from, Instant to) {
        return combine(withTotals(resultRepository.performanceByExecutionBetween(activeIds(), from, to)));
    }

    /** Executions en cours, a exclure ; jamais vide (NOT IN () est refuse par plusieurs SGBD) : un identifiant inexistant sinon. */
    private List<UUID> activeIds() {
        List<UUID> ids = resultRepository.activeExecutionIds(ACTIVE);
        return ids.isEmpty() ? List.of(new UUID(0L, 0L)) : ids;
    }

    /**
     * Complete chaque groupe [execution.id, requetes retenues, somme des temps, nombre de temps] avec les
     * colonnes de son execution [reussies, echecs, duree, duree en pause], lues par lots d'identifiants.
     */
    private List<Object[]> withTotals(List<Object[]> groups) {
        java.util.Map<UUID, Object[]> totals = new java.util.HashMap<>();
        List<UUID> ids = groups.stream().map(g -> (UUID) g[0]).toList();
        for (int from = 0; from < ids.size(); from += IDS_PER_QUERY) {
            for (Object[] t : resultRepository.executionTotals(ids.subList(from, Math.min(ids.size(), from + IDS_PER_QUERY)))) {
                totals.put((UUID) t[0], t);
            }
        }
        List<Object[]> rows = new java.util.ArrayList<>(groups.size());
        for (Object[] g : groups) {
            Object[] t = totals.get((UUID) g[0]);
            if (t == null) continue; // execution supprimee entre les deux lectures
            rows.add(new Object[] {g[0], g[1], g[2], g[3], t[1], t[2], t[3], t[4]});
        }
        return rows;
    }

    /**
     * Lignes [execution.id, requetes retenues, somme des temps, nombre de temps, reussies, echecs, duree,
     * duree en pause].
     */
    static Averages combine(List<Object[]> rows) {
        double timeSum = 0;
        long timeCount = 0;
        BigDecimal throughputSum = BigDecimal.ZERO;
        long throughputWeight = 0;
        BigDecimal errorSum = BigDecimal.ZERO;
        long errorWeight = 0;
        for (Object[] row : rows) {
            long rowsKept = ((Number) row[1]).longValue();
            if (row[2] != null) timeSum += ((Number) row[2]).doubleValue();
            timeCount += row[3] == null ? 0 : ((Number) row[3]).longValue();
            int requests = intValue(row[4]) + intValue(row[5]);
            BigDecimal throughput = throughput(requests, longValue(row[6]), longValue(row[7]));
            if (throughput != null) {
                throughputSum = throughputSum.add(throughput.multiply(BigDecimal.valueOf(rowsKept)));
                throughputWeight += rowsKept;
            }
            BigDecimal errorRate = errorRate(intValue(row[5]), requests);
            if (errorRate != null) {
                errorSum = errorSum.add(errorRate.multiply(BigDecimal.valueOf(rowsKept)));
                errorWeight += rowsKept;
            }
        }
        return new Averages(
                timeCount == 0 ? null : timeSum / timeCount,
                throughputWeight == 0 ? null : throughputSum.doubleValue() / throughputWeight,
                errorWeight == 0 ? null : errorSum.doubleValue() / errorWeight);
    }

    /** Debit d'une execution terminee : requetes / duree active (pauses exclues), meme formule que le rapport. */
    public static BigDecimal throughput(int requests, Long durationMs, Long pausedMs) {
        if (durationMs == null) return null;
        long active = Math.max(0L, durationMs - (pausedMs == null ? 0L : pausedMs));
        return ThroughputCalculator.compute(requests, active);
    }

    /** Taux d'erreurs d'une execution : echecs x 100 / requetes, 2 decimales ; null sans requete. */
    public static BigDecimal errorRate(int failed, int requests) {
        if (requests <= 0) return null;
        return BigDecimal.valueOf(failed).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(requests), 2, RoundingMode.HALF_UP);
    }

    private static int intValue(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static Long longValue(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
