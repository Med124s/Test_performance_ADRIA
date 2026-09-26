package com.loadpilot.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mesure REELLE (jamais simulee) rattachee a une Execution - voir
 * service.impl.MetricGenerationService, seule source actuelle de Metric
 * (generation automatique apres chaque Execution, un Metric par
 * ExecutionStepResult).
 *
 * "application"/"scenario" sont nullables au niveau du schema : ils sont
 * TOUJOURS renseignes pour un Metric issu d'une Execution (la chaine
 * Execution -> Scenario -> Application est toujours complete), mais le
 * schema laisse la place a une future source de Metric qui n'aurait pas
 * cette chaine complete. "step"/"execution" sont en revanche obligatoires :
 * un Metric issu d'une Execution reference toujours au minimum ces deux-la.
 */
@Entity
@Table(name = "metric")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Metric {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", updatable = false)
    private Application application;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", updatable = false)
    private Scenario scenario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", nullable = false, updatable = false)
    private Step step;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    private Execution execution;

    /** Millisecondes - copie de ExecutionStepResult.responseTime correspondant. */
    @Column(name = "response_time")
    private Integer responseTime;

    /** Code HTTP reel recu - null en cas d'echec technique (aucune reponse). */
    @Column(name = "status_code")
    private Integer statusCode;

    /**
     * Requetes/seconde, calcule au niveau de l'EXECUTION entiere (steps
     * reellement executes / duree totale en secondes) - voir
     * MetricGenerationService#computeThroughput. Cette meme valeur
     * execution-level est reportee sur chaque Metric de cette Execution :
     * ce n'est PAS une mesure individuelle par Step. Null si la duree est
     * nulle/nulle-ou-egale-a-zero.
     */
    @Column(name = "throughput", precision = 12, scale = 3)
    private BigDecimal throughput;

    /**
     * Pourcentage (0-100) = failedSteps / STEPS REELLEMENT EXECUTES * 100
     * (PAS totalSteps : l'execution s'arrete au premier echec, voir
     * Execution/HttpClientExecutionEngine - totalSteps peut donc etre
     * superieur au nombre de steps reellement executes). Null si aucun
     * step execute.
     */
    @Column(name = "error_rate", precision = 5, scale = 2)
    private BigDecimal errorRate;

    /** Reprend le "captured_at" du ExecutionStepResult correspondant. */
    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;
}
