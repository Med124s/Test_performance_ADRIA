package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.NotificationType;
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

/** Notification destinee a un utilisateur (fin d'execution, planification declenchee ou echouee). */
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false, updatable = false)
    private AppUser recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private NotificationType type;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "message", length = 1000, updatable = false)
    private String message;

    /** Execution a l'origine de la notification ; null si aucune execution n'a ete creee. */
    @Column(name = "related_execution_id", updatable = false)
    private UUID relatedExecutionId;

    /** Planification a l'origine de la notification ; null pour un lancement manuel. */
    @Column(name = "related_schedule_id", updatable = false)
    private UUID relatedScheduleId;

    @Column(name = "read", nullable = false)
    @Builder.Default
    private boolean read = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;
}
