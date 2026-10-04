package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.enums.ApplicationStatus;
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
 * Application cible declaree par l'utilisateur (ex: "Banking Test API") -
 * voir ApplicationService pour le test de disponibilite reel qui alimente
 * "status".
 */
@Entity
@Table(name = "application")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "url", nullable = false)
    private String url;

    /** Nullable : voir ApplicationStatus - null tant qu'aucun test reel n'a ete lance. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ApplicationStatus status;

    /** Archivage logique : masque des listes par defaut, historique conserve,
     * restaurable (voir migration 017). */
    @Column(name = "archived", nullable = false)
    @Builder.Default
    private boolean archived = false;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Message lisible du dernier test de connectivite (jamais de secret). */
    @Column(name = "status_message")
    private String statusMessage;

    /** Date du dernier test ; null si aucun test valide (jamais teste ou
     * modifie depuis). */
    @Column(name = "status_checked_at")
    private Instant statusCheckedAt;

    /** Authentification appliquee automatiquement aux requetes vers cette
     * Application (voir ApplicationAuthType) - NONE par defaut. */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    @Builder.Default
    private ApplicationAuthType authType = ApplicationAuthType.NONE;

    /** Parametres du type choisi, en JSON (voir service.auth.ApplicationAuthConfig).
     * Peut contenir des secrets : jamais expose tel quel par l'API. */
    @Column(name = "auth_config")
    private String authConfig;

    /** Utilisateur (projection locale, voir AppUser) ayant cree cette application. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
