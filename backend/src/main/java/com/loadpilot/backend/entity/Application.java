package com.loadpilot.backend.entity;

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

    /** Identite declarative libre (ex: "REST", "SOAP", "GraphQL") - jamais
     * interpretee par le moteur d'execution, purement informative. */
    @Column(name = "type")
    private String type;

    /** Methode d'authentification declarative (ex: "Bearer Token", "API Key",
     * "Basic Auth") - purement informative, ne modifie pas les requetes
     * envoyees par le moteur (voir Step.headers pour les en-tetes reellement
     * envoyes). */
    @Column(name = "auth_method")
    private String authMethod;

    /** Secret potentiel - JAMAIS relu par l'API (voir ApplicationResponse/
     * ApplicationMapper, qui l'omettent explicitement). Ecriture seule,
     * comme un mot de passe. */
    @Column(name = "auth_token", length = 500)
    private String authToken;

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
