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

/** Ancienne table de metriques, plus ecrite ni lue ; ses lignes sont effacees par la purge des resultats. */
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

    /** null si l'etape a ete supprimee depuis. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", updatable = false)
    private Step step;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    private Execution execution;

    /** Temps de reponse en millisecondes. */
    @Column(name = "response_time")
    private Integer responseTime;

    /** Code HTTP recu ; null en cas d'echec technique. */
    @Column(name = "status_code")
    private Integer statusCode;

    /** Debit de l'execution (requetes/seconde). */
    @Column(name = "throughput", precision = 12, scale = 3)
    private BigDecimal throughput;

    /** Taux d'erreurs de l'execution (%). */
    @Column(name = "error_rate", precision = 5, scale = 2)
    private BigDecimal errorRate;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;
}
