package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.ScheduleType;
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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Planification du lancement d'un scenario (date unique ou expression cron). */
@Entity
@Table(name = "scheduled_execution")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false)
    private Scenario scenario;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false)
    private ScheduleType scheduleType;

    /** Requis et non-null uniquement si scheduleType == RECURRING_CRON. */
    @Column(name = "cron_expression")
    private String cronExpression;

    /** Requis si scheduleType == ONE_TIME : date et heure absolues. */
    @Column(name = "run_at")
    private Instant runAt;

    /** Fuseau horaire IANA (ex : "Africa/Casablanca"). */
    @Column(name = "timezone", nullable = false)
    private String timezone;

    /** Prochain lancement ; null = plus aucun lancement automatique. */
    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** Desactivee par la desactivation de son scenario. */
    @Column(name = "paused_by_scenario", nullable = false)
    @Builder.Default
    private boolean pausedByScenario = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    /** Derniere execution lancee par cette planification. */
    @Column(name = "last_execution_id")
    private UUID lastExecutionId;

    /** Raison du dernier echec de declenchement ; null si le dernier a reussi. */
    @Column(name = "last_trigger_error", length = 500)
    private String lastTriggerError;

    /** Date de fin optionnelle (planification recurrente) : aucune occurrence apres cet instant. */
    @Column(name = "end_at")
    private Instant endAt;

    /** Quand notifier a la fin d'une execution planifiee. */
    @Enumerated(EnumType.STRING)
    @Column(name = "notify_mode", nullable = false)
    @Builder.Default
    private com.loadpilot.backend.enums.ScheduleNotifyMode notifyMode = com.loadpilot.backend.enums.ScheduleNotifyMode.ALWAYS;
}
