package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.StopMode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Une execution reelle d'un Scenario (voir service.execution). "startedAt"
 * est fixe explicitement par le service au moment ou l'execution demarre
 * reellement - pas un @CreationTimestamp generique. "finishedAt"/"duration"
 * restent null tant que le statut est RUNNING (voir ExecutionStatus).
 */
@Entity
@Table(name = "execution")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false, updatable = false)
    private Scenario scenario;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionStatus status;

    /**
     * Copie figee des parametres de charge du Scenario au moment du
     * lancement (P0-A) - jamais recalculee depuis le Scenario courant, pour
     * que l'historique reste fidele meme si le Scenario est reconfigure
     * plus tard (meme principe de denormalisation deja utilise par
     * Metric.throughput/errorRate, voir MetricGenerationService).
     */
    @Builder.Default
    @Column(name = "virtual_users", nullable = false)
    private Integer virtualUsers = 1;

    @Builder.Default
    @Column(name = "ramp_up_seconds", nullable = false)
    private Integer rampUpSeconds = 0;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "iterations")
    private Integer iterations;

    /** Copie figee de Scenario.stopMode au lancement - voir StopMode. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "stop_mode", nullable = false)
    private StopMode stopMode = StopMode.AUTO;

    @Column(name = "total_steps", nullable = false)
    private Integer totalSteps;

    @Column(name = "successful_steps", nullable = false)
    private Integer successfulSteps;

    @Column(name = "failed_steps", nullable = false)
    private Integer failedSteps;

    /** Millisecondes - colonne "duration_ms" pour la clarte. */
    @Column(name = "duration_ms")
    private Long duration;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /**
     * P1-B — utilisateur reellement a l'origine de cette Execution (celui
     * qui a clique "Lancer", ou le proprietaire de la ScheduledExecution qui
     * l'a declenchee) - null pour toute Execution creee AVANT cette
     * migration (jamais reconstruit retroactivement, voir 011). Sert
     * UNIQUEMENT a determiner le destinataire de la Notification
     * EXECUTION_SUCCESS/FAILED/CANCELLED (voir ExecutionServiceImpl et
     * ExecutionRecoveryRunner) - jamais un remplacement d'AuditLog.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triggered_by_app_user_id", updatable = false)
    private AppUser triggeredBy;

    @Builder.Default
    @OneToMany(mappedBy = "execution", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExecutionStepResult> results = new ArrayList<>();
}
