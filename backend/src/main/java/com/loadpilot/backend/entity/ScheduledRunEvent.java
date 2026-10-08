package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.ScheduledRunOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Lancement planifie qui n'a PAS donne d'execution (ignore, manque, refuse), avec sa raison en francais -
 * affiche dans l'historique de la planification a cote des executions reelles (execution.schedule_id).
 * Supprime avec sa planification (cle etrangere en cascade).
 */
@Entity
@Table(name = "scheduled_run_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledRunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    /** Heure a laquelle le lancement etait prevu. */
    @Column(name = "planned_at", nullable = false, updatable = false)
    private Instant plannedAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private ScheduledRunOutcome outcome;

    @Column(name = "reason", nullable = false, length = 500, updatable = false)
    private String reason;
}
