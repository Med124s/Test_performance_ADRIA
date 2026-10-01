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

/**
 * Etape HTTP appartenant a exactement un Scenario. Ne peut pas exister sans
 * Scenario (FK non-nulle, voir 004-create-step.xml). Aucun createdBy (voir
 * Phase 8 - contrairement a Application/Scenario, cette entite n'en a pas
 * ete demandee).
 *
 * "order" est un mot reserve SQL dans plusieurs SGBD (dont H2 et
 * PostgreSQL) - la colonne est nommee "step_order" (voir @Column), tandis
 * que le champ Java garde le nom naturel "order" (pas un mot reserve en
 * Java).
 */
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

    /** JSON/texte libre (headers HTTP), optionnel - jamais interprete ici. */
    @Column(name = "headers", length = 4000)
    private String headers;

    /** Corps de requete libre, optionnel. */
    @Column(name = "body", length = 4000)
    private String body;

    @Column(name = "step_order", nullable = false)
    private Integer order;

    /** Texte libre documentant l'objectif de l'etape - jamais utilise par
     * le moteur d'execution (documentation pure, comme Scenario.description). */
    @Column(name = "description", length = 1000)
    private String description;

    /** Pause reelle APRES l'envoi de la requete de cette etape (en plus de
     * thinkTimeMs, qui reste une pause AVANT la requete suivante) - voir
     * HttpClientExecutionEngine. null = aucune pause supplementaire. */
    @Column(name = "pacing_after_ms")
    private Integer pacingAfterMs;

    @Column(name = "expected_status")
    private Integer expectedStatus;

    /** P1-Q Etape B — pause optionnelle apres CETTE etape precise, en plus
     * du think time de Scenario (applique apres l'iteration complete,
     * inchange). null = aucune pause supplementaire (comportement
     * historique). */
    @Column(name = "think_time_ms")
    private Integer thinkTimeMs;

    /** P1-Q Etape B — timeout HTTP specifique a cette etape. null = utilise
     * le timeout global existant (app.execution.timeout-seconds). */
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    /** P1-Q Etape B — null = comportement global existant
     * (HttpClient.Redirect.NORMAL). false = ne jamais suivre une
     * redirection pour cette etape. true = suivre explicitement (memes
     * effet que null, autorise pour la symetrie du formulaire). */
    @Column(name = "follow_redirects")
    private Boolean followRedirects;

    /** P1-Q Etape B — sous-chaine optionnelle que le corps de la reponse
     * doit contenir pour que l'etape soit en succes (en plus de
     * expectedStatus, jamais a la place). null = comportement historique
     * inchange (corps jamais lu). */
    @Column(name = "assertion_body_contains", length = 500)
    private String assertionBodyContains;

    /** Master prompt final (Lot B) — nom de la variable a produire depuis
     * la reponse de CETTE etape, disponible pour les etapes SUIVANTES du
     * MEME utilisateur virtuel (jamais partagee entre VUs). N'a d'effet que
     * si captureJsonPath est AUSSI renseigne (capture explicitement
     * configuree, jamais automatique) - voir JsonPathExtractor. */
    @Column(name = "capture_variable_name")
    private String captureVariableName;

    /** Master prompt final (Lot B) — chemin (sous-ensemble simple, voir
     * JsonPathExtractor : segments separes par des points, prefixe "$."
     * optionnel, ex. "$.token"/"token"/"data.token" - jamais de tableaux/
     * filtres/wildcards, aucune dependance JSONPath ajoutee) dans le corps
     * JSON de la reponse. null ou reponse non-JSON/chemin introuvable =
     * aucune variable produite, jamais une erreur d'execution. */
    @Column(name = "capture_json_path", length = 500)
    private String captureJsonPath;

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
