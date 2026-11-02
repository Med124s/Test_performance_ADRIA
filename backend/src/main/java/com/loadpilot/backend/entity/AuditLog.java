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

/** Entree du journal d'audit ; jamais modifiee apres creation. */
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

    /** Claim "sub" du JWT ; null si aucun utilisateur authentifie. */
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

    @Column(name = "date", nullable = false, updatable = false)
    private Instant date;

    /** Adresse IP du client ; null hors requete HTTP. */
    @Column(name = "ip", length = 45, updatable = false)
    private String ip;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, updatable = false)
    private AuditResult result;

    /** Description libre, sans secret. */
    @Column(name = "description", length = 500, updatable = false)
    private String description;

    /** USER (utilisateur connecte) ou SYSTEM (action automatique : planification, fin d'execution, purge...). */
    @Builder.Default
    @Column(name = "actor_type", nullable = false, length = 10, updatable = false)
    private String actorType = "USER";

    /** Origine d'une action automatique ou importee, ex. "Planification : Test nocturne". */
    @Column(name = "actor_source", updatable = false)
    private String actorSource;

    /** Objet concerne (voir service.audit.AuditTargetType) - pour un nom lisible et un lien. */
    @Column(name = "target_type", length = 30, updatable = false)
    private String targetType;

    @Column(name = "target_id", length = 64, updatable = false)
    private String targetId;

    /** Nom lisible de l'objet AU MOMENT de l'action (reste lisible si l'objet est supprime). */
    @Column(name = "target_label", updatable = false)
    private String targetLabel;

    /** Changements avant -> apres (JSON [{label, before, after}]), jamais de secret. */
    @Column(name = "changes", length = 4000, updatable = false)
    private String changes;

    /** Navigateur (en-tete User-Agent, tronque) de la requete a l'origine de l'action. */
    @Column(name = "user_agent", length = 300, updatable = false)
    private String userAgent;

    /** Identifiant de l'evenement externe importe (evenement Keycloak) : jamais importe deux fois. */
    @Column(name = "external_id", length = 100, updatable = false, unique = true)
    private String externalId;
}
