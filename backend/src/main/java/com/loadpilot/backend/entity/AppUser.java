package com.loadpilot.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Projection LOCALE, non-authoritative, d'un utilisateur authentifie via
 * Keycloak - sert uniquement de point de jonction pour de futures relations
 * (ex: AuditLog.userId) et de cache d'affichage (username/name/email tels
 * que vus au dernier login), jamais pour l'authentification elle-meme.
 *
 * INTERDIT ICI (voir Phase 5 - authentification 100% deleguee a Keycloak) :
 * aucun mot de passe, hash, credential, refresh token ou client secret.
 * L'identite et les roles reels viennent toujours du Jwt (voir CurrentUser),
 * jamais de cette table.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Claim "sub" du Jwt Keycloak - identifiant externe stable, immuable. */
    @Column(name = "keycloak_subject", nullable = false, unique = true, updatable = false)
    private String keycloakSubject;

    @Column(name = "username")
    private String username;

    @Column(name = "name")
    private String name;

    @Column(name = "email")
    private String email;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /**
     * P1-D — PREFERENCE reelle de l'utilisateur (jamais ecrasee par
     * AppUserSyncService.sync, qui ne touche jamais ce champ - contrairement
     * a username/name/email, resynchronises a chaque login depuis le Jwt).
     * Identifiant de fuseau horaire IANA (ex: "Africa/Casablanca"), valide
     * via ZoneId.of() avant toute ecriture (voir ProfileServiceImpl) -
     * nullable : aucune valeur par defaut fabriquee, un utilisateur qui n'a
     * jamais choisi de fuseau n'en a simplement pas (le frontend propose son
     * propre fuseau navigateur comme suggestion, jamais un choix impose).
     */
    @Column(name = "timezone")
    private String timezone;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
