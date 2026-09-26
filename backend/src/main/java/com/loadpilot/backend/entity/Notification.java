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

/**
 * Notification REELLE (P1-B) destinee a un unique utilisateur (voir
 * "recipient") - generee UNIQUEMENT depuis de vrais evenements metier
 * (Execution terminale, ScheduledExecution declenchee/echouee - voir
 * NotificationServiceImpl et ses appelants). Jamais de notification de
 * demonstration, jamais stockee cote client (localStorage/JSON Server).
 *
 * "recipient" est une VRAIE FK vers AppUser (contrairement a AuditLog qui
 * denormalise userId/username en texte libre) : une Notification n'a de
 * sens QUE tant que son destinataire existe reellement, et doit rester
 * requetable efficacement par destinataire (voir
 * NotificationRepository#findByRecipientId*) - un cas d'usage different de
 * l'audit (trace immuable independante du cycle de vie d'AppUser). Meme
 * choix de modelisation que Scenario.createdBy (voir son Javadoc).
 */
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

    /** Id (pas une FK) de l'Execution reelle a l'origine de cet evenement -
     * null pour SCHEDULE_FAILED lorsque aucune Execution n'a meme pu etre
     * creee (ex: limite de capacite atteinte avant toute ecriture). */
    @Column(name = "related_execution_id", updatable = false)
    private UUID relatedExecutionId;

    /** Id (pas une FK) de la ScheduledExecution a l'origine de cet evenement -
     * null pour une Execution lancee manuellement. */
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
