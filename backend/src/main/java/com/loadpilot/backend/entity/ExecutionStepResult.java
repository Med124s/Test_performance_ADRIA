package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.HttpMethod;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
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
 * Resultat REEL d'une etape au sein d'une Execution - jamais une valeur
 * simulee (voir service.execution.HttpClientExecutionEngine).
 *
 * "error" ne doit jamais contenir de header/valeur potentiellement sensible
 * (Authorization, cookies...) - uniquement un message technique generique
 * (code HTTP, classe d'exception reseau).
 */
@Entity
@Table(name = "execution_step_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionStepResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false, updatable = false)
    private Execution execution;

    /** Null si l'etape a ete supprimee depuis (FK ON DELETE SET NULL,
     * migration 017) - l'historique reste lisible via la copie ci-dessous. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", updatable = false)
    private Step step;

    /** Copie de l'etape au moment de l'execution (nom, methode, URL brute). */
    @Column(name = "step_name")
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_method", length = 10)
    private HttpMethod stepMethod;

    @Column(name = "step_url", length = 2048)
    private String stepUrl;

    /** Null en cas d'echec technique (timeout/reseau) - aucune reponse HTTP recue. */
    @Column(name = "http_status")
    private Integer httpStatus;

    /** Millisecondes. */
    @Column(name = "response_time_ms")
    private Long responseTime;

    @Column(name = "success", nullable = false)
    private Boolean success;

    /** Utilisateur virtuel (0..n-1) et iteration (0..) ayant produit ce resultat. */
    @Column(name = "virtual_user")
    private Integer virtualUser;

    @Column(name = "iteration")
    private Integer iteration;

    /** URL finale reellement appelee (variables injectees et encodees, secrets masques). */
    @Column(name = "request_url", length = 2048)
    private String requestUrl;

    @Column(name = "error", length = 2000)
    private String error;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant timestamp;

    /** Identifiant de l'etape, ou null si elle a ete supprimee depuis. */
    public java.util.UUID stepIdOrNull() {
        return step != null ? step.getId() : null;
    }

    /** Nom affiche : copie enregistree, sinon etape encore existante. */
    public String displayStepName() {
        if (stepName != null) return stepName;
        return step != null ? step.getName() : "Étape supprimée";
    }

    public HttpMethod displayStepMethod() {
        if (stepMethod != null) return stepMethod;
        return step != null ? step.getMethod() : null;
    }

    public String displayStepUrl() {
        if (stepUrl != null) return stepUrl;
        return step != null ? step.getUrl() : "";
    }
}
