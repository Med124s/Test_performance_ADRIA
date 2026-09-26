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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * P1-D — préférence RÉELLE d'un utilisateur pour UN type de Notification
 * (voir enums.NotificationType) - modèle "opt-out" : l'ABSENCE de ligne
 * pour un (appUser, notificationType) donné signifie "activé" (comportement
 * historique inchangé, voir NotificationPreferenceServiceImpl) - une ligne
 * n'est créée qu'au premier changement explicite de préférence, jamais
 * pré-remplie pour tous les types au premier login (éviterait 5 lignes
 * inutiles par utilisateur qui ne change jamais rien).
 *
 * Table dédiée (jamais une colonne JSON/liste sur AppUser) : un type de
 * notification est une valeur d'enum stable et un besoin métier clair
 * (contrairement à un magasin clé/valeur générique, explicitement interdit
 * pour cette phase).
 */
@Entity
@Table(name = "notification_preference")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", nullable = false, updatable = false)
    private AppUser appUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, updatable = false)
    private NotificationType notificationType;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
