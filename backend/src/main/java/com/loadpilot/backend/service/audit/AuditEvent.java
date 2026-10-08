package com.loadpilot.backend.service.audit;

import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Une entree a ecrire dans le journal d'audit (voir AuditLogService#record(AuditEvent)).
 *
 * <pre>
 * auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.SCENARIO)
 *         .target(AuditTargetType.SCENARIO, id, name)
 *         .change("Utilisateurs virtuels", 20, 40)
 *         .description("Charge du scénario modifiée"));
 * </pre>
 *
 * Par defaut : resultat SUCCESS, acteur = utilisateur de la requete courante.
 * {@link #system(String)} : action automatique (acteur « Système » + source).
 * Tous les textes passent par AuditSanitizer avant l'ecriture : jamais de secret.
 */
public final class AuditEvent {

    private final AuditAction action;
    private final AuditModule module;
    private AuditResult result = AuditResult.SUCCESS;
    private String description;
    private AuditTargetType targetType;
    private String targetId;
    private String targetLabel;
    private final List<AuditChange> changes = new ArrayList<>();
    private boolean system;
    private String source;
    private boolean consultation;
    // Evenements importes (systeme d'authentification) : acteur, date, IP et identifiant fournis.
    private String actorUserId;
    private String actorUsername;
    private Instant date;
    private String ip;
    private String externalId;

    private AuditEvent(AuditAction action, AuditModule module) {
        this.action = Objects.requireNonNull(action);
        this.module = Objects.requireNonNull(module);
    }

    public static AuditEvent of(AuditAction action, AuditModule module) {
        return new AuditEvent(action, module);
    }

    public AuditEvent target(AuditTargetType type, Object id, String label) {
        this.targetType = type;
        this.targetId = id == null ? null : id.toString();
        this.targetLabel = label;
        return this;
    }

    public AuditEvent description(String description) {
        this.description = description;
        return this;
    }

    /** Ajoute « libelle : avant -> apres », seulement si la valeur a reellement change. */
    public AuditEvent change(String label, Object before, Object after) {
        String b = format(before);
        String a = format(after);
        if (!Objects.equals(b, a)) {
            changes.add(new AuditChange(label, b, a));
        }
        return this;
    }

    /** Compare deux photographies (voir AuditSnapshots) : chaque valeur modifiee devient « avant -> apres ». */
    public AuditEvent diff(java.util.Map<String, Object> before, java.util.Map<String, Object> after) {
        if (before == null || after == null) {
            return this;
        }
        after.forEach((label, value) -> change(label, before.get(label), value));
        return this;
    }

    public AuditEvent changes(List<AuditChange> more) {
        if (more != null) {
            more.forEach(c -> change(c.label(), c.before(), c.after()));
        }
        return this;
    }

    /** Echec : raison francaise sans nom d'exception (voir AuditReasons). */
    public AuditEvent failed(Throwable e) {
        return failed(AuditReasons.of(e));
    }

    public AuditEvent failed(String reason) {
        this.result = AuditResult.FAILURE;
        this.description = description == null ? reason : description + " — " + reason;
        return this;
    }

    public AuditEvent result(AuditResult result) {
        this.result = result;
        return this;
    }

    /** Action automatique : acteur « Système », source lisible (ex. « Planification : Test nocturne »). */
    public AuditEvent system(String source) {
        this.system = true;
        this.source = source;
        return this;
    }

    /** Simple consultation : journalisee seulement si le reglage « Journaliser aussi les consultations » est actif. */
    public AuditEvent consultation() {
        this.consultation = true;
        return this;
    }

    /** Evenement importe du systeme d'authentification (acteur, date et IP connus). */
    public AuditEvent imported(String externalId, Instant date, String userId, String username, String ip, String source) {
        this.externalId = externalId;
        this.date = date;
        this.actorUserId = userId;
        this.actorUsername = username;
        this.ip = ip;
        this.source = source;
        return this;
    }

    static String format(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b ? "Oui" : "Non";
        }
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    public AuditAction action() { return action; }
    public AuditModule module() { return module; }
    public AuditResult result() { return result; }
    public String description() { return description; }
    public AuditTargetType targetType() { return targetType; }
    public String targetId() { return targetId; }
    public String targetLabel() { return targetLabel; }
    public List<AuditChange> changes() { return List.copyOf(changes); }
    public boolean isSystem() { return system; }
    public String source() { return source; }
    public boolean isConsultation() { return consultation; }
    public String actorUserId() { return actorUserId; }
    public String actorUsername() { return actorUsername; }
    public Instant date() { return date; }
    public String ip() { return ip; }
    public String externalId() { return externalId; }
    public boolean isImported() { return externalId != null; }
}
