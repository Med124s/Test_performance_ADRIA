package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.HttpMethod;
import com.loadpilot.backend.enums.StepStatus;
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

/** Etape HTTP d'un scenario. */
@Entity
@Table(name = "step")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Step {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false)
    private Scenario scenario;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false)
    private HttpMethod method;

    @Column(name = "url", nullable = false)
    private String url;

    /** En-tetes HTTP (JSON), optionnels. */
    @Column(name = "headers", length = 4000)
    private String headers;

    /** Corps de requete libre, optionnel. */
    @Column(name = "body", length = 4000)
    private String body;

    @Column(name = "step_order", nullable = false)
    private Integer order;

    @Column(name = "expected_status")
    private Integer expectedStatus;

    /** Pause apres cette etape (ms) ; null = aucune. */
    @Column(name = "think_time_ms")
    private Integer thinkTimeMs;

    /** Timeout HTTP de cette etape ; null = timeout global. */
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    /** null = redirections suivies ; false = jamais suivies. */
    @Column(name = "follow_redirects")
    private Boolean followRedirects;

    /** Texte que le corps de la reponse doit contenir ; null = aucune verification. */
    @Column(name = "assertion_body_contains", length = 500)
    private String assertionBodyContains;

    /** Nom de la variable capturee dans la reponse, disponible pour les etapes suivantes du meme utilisateur virtuel. */
    @Column(name = "capture_variable_name")
    private String captureVariableName;

    /** Chemin de la valeur a capturer (ex : "$.token", "data.accounts[0].id"). */
    @Column(name = "capture_json_path", length = 500)
    private String captureJsonPath;

    /** Source de la capture : JSON (corps) ou HEADER (nom de l'en-tete, ou "Set-Cookie:NOM") ; null = JSON. */
    @Enumerated(EnumType.STRING)
    @Column(name = "capture_source", length = 20)
    private com.loadpilot.backend.enums.CaptureSource captureSource;

    /** Temps de reponse maximal (ms) : au-dela, l'etape echoue (voir StepChecks) ; null = aucun. */
    @Column(name = "max_response_time_ms")
    private Integer maxResponseTimeMs;

    /** Assertion JSON : champ a verifier (meme syntaxe que la capture) ; null = aucune. */
    @Column(name = "assertion_json_path", length = 500)
    private String assertionJsonPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "assertion_json_operator", length = 16)
    private com.loadpilot.backend.enums.JsonAssertionOperator assertionJsonOperator;

    @Column(name = "assertion_json_value", length = 1000)
    private String assertionJsonValue;

    /** Variables de chemin (:nom / {nom}) : objet JSON {"nom": "valeur ou ${variable}"}. */
    @Column(name = "path_params", length = 4000)
    private String pathParams;

    /** Parametres de requete : tableau JSON [{"key","value","enabled"}]. */
    @Column(name = "query_params", length = 8000)
    private String queryParams;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StepStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
