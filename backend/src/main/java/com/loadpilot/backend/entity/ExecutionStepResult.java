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
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Resultat REEL d'une etape au sein d'une Execution - jamais une valeur
 * simulee (voir service.execution.HttpClientExecutionEngine).
 *
 * "error" ne doit jamais contenir de header/valeur potentiellement sensible
 * (Authorization, cookies...) - uniquement un message technique generique
 * (code HTTP, classe d'exception reseau).
 */
@Entity
@Table(name = "execution_step_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionStepResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    private Execution execution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", nullable = false, updatable = false)
    private Step step;

    /** Null en cas d'echec technique (timeout/reseau) - aucune reponse HTTP recue. */
    @Column(name = "http_status")
    private Integer httpStatus;

    /** Millisecondes. */
    @Column(name = "response_time_ms")
    private Long responseTime;

    @Column(name = "success", nullable = false)
    private Boolean success;

    @Column(name = "error", length = 2000)
    private String error;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant timestamp;
}
