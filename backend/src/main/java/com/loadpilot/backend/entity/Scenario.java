package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.ScenarioStatus;
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
 * Scenario de test appartenant a exactement une Application. Ne peut pas
 * exister sans Application (FK non-nulle, voir 003-create-scenario.xml).
 */
@Entity
@Table(name = "scenario")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ScenarioStatus status;

    /**
     * Parametres de charge REELLEMENT utilises par HttpClientExecutionEngine
     * (voir LoadTestSpec) - jamais decoratifs (Phase P0-A). Valeurs par
     * defaut (1 VU, 0s de ramp-up, aucune duree/iteration, 0ms de think
     * time) reproduisent exactement le comportement historique a une seule
     * passe pour tout Scenario cree avant cette phase (migration
     * 010-add-load-test-parameters.xml).
     */
    @Column(name = "virtual_users", nullable = false)
    @Builder.Default
    private Integer virtualUsers = 1;

    @Column(name = "ramp_up_seconds", nullable = false)
    @Builder.Default
    private Integer rampUpSeconds = 0;

    /** Null = pas de limite de duree (voir iterations). */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /** Null = pas de limite d'iterations (voir durationSeconds). Si les
     * deux sont null, chaque utilisateur virtuel execute exactement UNE
     * iteration (comportement historique). */
    @Column(name = "iterations")
    private Integer iterations;

    @Column(name = "think_time_ms", nullable = false)
    @Builder.Default
    private Integer thinkTimeMs = 0;

    /** P1-Q Etape B — donnees CSV brutes optionnelles (premiere ligne = noms
     * de colonnes/variables), une ligne distribuee par utilisateur virtuel
     * (cyclique si moins de lignes que de VUs) pour alimenter des variables
     * ${nomColonne} substituees dans les Steps (voir
     * HttpClientExecutionEngine). null = aucune variable de donnees,
     * comportement historique inchange. */
    /** Longueur alignee sur la contrainte @Size(max=50000) de
     * ScenarioRequest.csvData - meme convention que headers/body de Step
     * (VARCHAR borne, jamais un TEXT/CLOB non portable de maniere identique
     * entre H2 (tests) et PostgreSQL (reel) - constat reel fait pendant
     * cette phase : "TEXT" y produit des types de colonne differents,
     * faisant echouer la validation de schema Hibernate). */
    @Column(name = "csv_data", length = 1_000_000)
    private String csvData;

    /** Variables du scenario (objet JSON {"nom": "valeur"}) - 3e source apres capture et CSV. */
    @Column(name = "variables", length = 20000)
    private String variables;

    @Enumerated(EnumType.STRING)
    @Column(name = "csv_distribution", nullable = false, length = 20)
    @Builder.Default
    private com.loadpilot.backend.enums.CsvDistribution csvDistribution = com.loadpilot.backend.enums.CsvDistribution.PER_VIRTUAL_USER;

    /** Detail des requetes conserve a chaque execution (voir RequestDetailRecorder). */
    @Enumerated(EnumType.STRING)
    @Column(name = "request_detail_mode", nullable = false, length = 30)
    @Builder.Default
    private com.loadpilot.backend.enums.RequestDetailMode requestDetailMode = com.loadpilot.backend.enums.RequestDetailMode.FAILURES_AND_SAMPLE;

    /** Etape de l'assistant atteinte (0..3) - brouillons uniquement. */
    @Column(name = "wizard_step")
    private Integer wizardStep;

    /** Etat brut de l'assistant (JSON, etapes incompletes comprises) - brouillons uniquement. */
    @Column(name = "draft_state", length = 1_000_000)
    private String draftState;

    /** Master prompt final (Lot A) — debit cible optionnel (requetes HTTP
     * demarrees par seconde, approximatif, partage entre tous les
     * utilisateurs virtuels de l'Execution) - voir
     * HttpClientExecutionEngine.PacingGate. null = aucun pacing,
     * comportement historique inchange. Ne remplace jamais virtualUsers/
     * rampUpSeconds/thinkTimeMs, qui restent tous actifs simultanement. */
    @Column(name = "target_rps")
    private Integer targetRps;

    /** Seuil de reussite : taux d'erreurs maximal en % (defaut 1 %). Au-dela : « Seuils depasses ». */
    @Column(name = "max_error_rate_percent", nullable = false)
    @Builder.Default
    private Double maxErrorRatePercent = 1.0;

    /** Seuil de reussite optionnel : P95 maximal (ms) sur l'ensemble des requetes ; null = pas de seuil. */
    @Column(name = "max_p95_ms")
    private Long maxP95Ms;

    /** Frein automatique : taux d'erreurs (%) au-dela duquel le test est arrete EN DIRECT (voir
     * ErrorRateBrake) ; null = desactive. Distinct du seuil de reussite, evalue a la fin. */
    @Column(name = "error_brake_rate_percent")
    private Double errorBrakeRatePercent;

    /** Fenetre glissante (secondes) du frein automatique ; null = valeur par defaut (30 s). */
    @Column(name = "error_brake_window_seconds")
    private Integer errorBrakeWindowSeconds;

    /** Ralentissement sur 429 (voir ThrottlePolicy) : null = actif (defaut), false = desactive (test de resilience). */
    @Column(name = "throttle_on_429")
    private Boolean throttleOn429;

    /** Attente apres un 429 sans en-tete Retry-After (secondes) ; null = 5 s. */
    @Column(name = "throttle_default_delay_seconds")
    private Integer throttleDefaultDelaySeconds;

    /** Rejeux au plus d'une etape apres un 429 ; null = 3. */
    @Column(name = "throttle_max_retries")
    private Integer throttleMaxRetries;

    /** Variation aleatoire du think time (± %, voir ThinkTime) ; null = think time fixe. */
    @Column(name = "think_time_variation_percent")
    private Integer thinkTimeVariationPercent;

    /** Archivage logique : masque des listes par defaut, historique conserve,
     * restaurable (voir migration 017). */
    @Column(name = "archived", nullable = false)
    @Builder.Default
    private boolean archived = false;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Utilisateur (projection locale, voir AppUser) ayant cree ce scenario. */
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
