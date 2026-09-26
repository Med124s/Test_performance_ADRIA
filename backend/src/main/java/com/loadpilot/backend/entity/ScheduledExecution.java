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

/**
 * Planification REELLE et PERSISTEE (P1-B) du lancement d'un Scenario -
 * survit a un redemarrage backend (contrairement a l'ancien
 * useScheduledExecutions frontend, decommissionne - voir le rapport P1-B).
 *
 * NE DUPLIQUE JAMAIS la configuration de charge du Scenario : au
 * declenchement (voir ScheduledExecutionPoller), c'est le MEME
 * ExecutionService.executeScheduled(...) que l'endpoint manuel qui est
 * appele - la copie figee des parametres de charge dans l'Execution
 * generee se fait donc EXACTEMENT comme pour un lancement manuel (voir
 * ExecutionTransactionHelper.prepareAndStart), avec la configuration du
 * Scenario TELLE QU'ELLE EST au moment reel du declenchement (jamais
 * retroactivement affectee par un edit ulterieur du Scenario apres coup,
 * puisque l'Execution deja generee est elle-meme immuable).
 *
 * "nextRunAt" est l'UNIQUE champ interroge par le poller
 * (ScheduledExecutionPoller) - toujours un Instant UTC absolu, jamais une
 * paire date/heure locale ambigue. "timezone" ne sert qu'a interpreter
 * "cronExpression" (heure de mur) lors du calcul de la PROCHAINE
 * occurrence (voir ScheduledExecutionServiceImpl#computeNextRunAt) - une
 * fois "nextRunAt" calcule, il est compare a Instant.now() independamment
 * de tout fuseau.
 */
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

    /** Requis et non-null uniquement si scheduleType == ONE_TIME - instant
     * absolu (deja converti depuis l'heure locale + timezone choisies par
     * l'utilisateur au moment de la creation, voir ScheduledExecutionServiceImpl). */
    @Column(name = "run_at")
    private Instant runAt;

    /** Identifiant de fuseau horaire IANA (ex: "Africa/Casablanca", "UTC") -
     * jamais un simple offset fixe (qui casserait silencieusement lors des
     * changements d'heure ete/hiver pour une planification RECURRING_CRON). */
    @Column(name = "timezone", nullable = false)
    private String timezone;

    /** Null = ne se declenchera plus jamais automatiquement (ONE_TIME deja
     * declenchee, ou schedule desactivee - voir ScheduledExecutionPoller). */
    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

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

    /** Id (pas une FK) de la derniere Execution reellement generee par cette
     * planification - simple pointeur d'affichage, jamais recalcule. */
    @Column(name = "last_execution_id")
    private UUID lastExecutionId;

    /** Raison du dernier ECHEC DE DECLENCHEMENT (avant meme la creation
     * d'une Execution - ex: limite de capacite, scenario introuvable) -
     * null si le dernier declenchement a reussi ou si aucun declenchement
     * n'a encore eu lieu. Jamais une exception complete/stacktrace. */
    @Column(name = "last_trigger_error", length = 500)
    private String lastTriggerError;
}
