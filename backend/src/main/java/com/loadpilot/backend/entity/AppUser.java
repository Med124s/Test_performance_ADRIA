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

/** Copie locale d'un utilisateur Keycloak, mise a jour a chaque connexion. */
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

    /** Claim "sub" du JWT Keycloak. */
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

    /** Fuseau horaire IANA choisi par l'utilisateur ; null si jamais choisi. */
    @Column(name = "timezone")
    private String timezone;

    /** Apparence choisie dans Parametres : LIGHT, DARK ou SYSTEM (suit l'appareil) ; null = jamais choisie. */
    @Column(name = "theme", length = 10)
    private String theme;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
