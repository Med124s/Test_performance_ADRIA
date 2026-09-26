package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
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
 * Trace immuable d'une action importante effectuee dans LoadPilot (Phase
 * 12) - jamais modifiee apres creation (toutes les colonnes sont
 * updatable = false), jamais supprimee par l'API (voir AuditLogService,
 * lecture seule).
 *
 * "userId"/"username" sont des COPIES denormalisees (pas une FK vers
 * AppUser) : un AuditLog doit rester lisible et coherent independamment du
 * cycle de vie futur d'AppUser (aucune suppression d'AppUser n'existe a ce
 * jour, mais l'independance est une garantie deliberee et standard d'un
 * systeme d'audit - une purge/anonymisation future d'AppUser ne doit jamais
 * casser silencieusement l'historique d'audit via une FK).
 *
 * INTERDIT ICI (voir Phase 12, section 2/20) : mot de passe, JWT complet,
 * access/refresh token, header Authorization, secret, cookie sensible -
 * uniquement des identifiants d'affichage (userId = "sub" Keycloak,
 * username) et une description texte libre mais jamais sensible (voir
 * AuditLogServiceImpl, qui construit ces descriptions).
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Claim "sub" du Jwt Keycloak (voir CurrentUser) - null si l'action n'a
     * pu etre associee a aucun utilisateur authentifie au moment ou elle a
     * ete enregistree (ex: aucun contexte de securite disponible). Jamais
     * un identifiant invente. */
    @Column(name = "user_id", updatable = false)
    private String userId;

    @Column(name = "username", updatable = false)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "module", nullable = false, updatable = false)
    private AuditModule module;

    /** Toujours Instant.now() genere cote backend au moment de
     * l'enregistrement (voir AuditLogServiceImpl.record) - jamais une date
     * fournie par le client. */
    @Column(name = "date", nullable = false, updatable = false)
    private Instant date;

    /** Adresse IP du pair TCP direct de la requete (voir
     * security.RequestIpResolver) - null si aucune requete HTTP n'est
     * associee a cette action. Jamais derivee d'un header client non
     * verifie (X-Forwarded-For). */
    @Column(name = "ip", length = 45, updatable = false)
    private String ip;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, updatable = false)
    private AuditResult result;

    /** Texte libre, JAMAIS de secret/token (voir AuditLogServiceImpl, qui
     * tronque et controle la construction de ces descriptions). */
    @Column(name = "description", length = 500, updatable = false)
    private String description;
}
