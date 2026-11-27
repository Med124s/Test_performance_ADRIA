package com.loadpilot.backend.entity;

import com.loadpilot.backend.enums.ExecutionStatus;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Execution d'un scenario ; finishedAt et duration restent null tant qu'elle n'est pas terminee. */
@Entity
@Table(name = "execution")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id", nullable = false, updatable = false)
    private Scenario scenario;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionStatus status;

    /** Parametres de charge copies du scenario au lancement. */
    @Builder.Default
    @Column(name = "virtual_users", nullable = false)
    private Integer virtualUsers = 1;

    @Builder.Default
    @Column(name = "ramp_up_seconds", nullable = false)
    private Integer rampUpSeconds = 0;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "iterations")
    private Integer iterations;

    @Column(name = "total_steps", nullable = false)
    private Integer totalSteps;

    @Column(name = "successful_steps", nullable = false)
    private Integer successfulSteps;

    @Column(name = "failed_steps", nullable = false)
    private Integer failedSteps;

    /** Duree en millisecondes. */
    @Column(name = "duration_ms")
    private Long duration;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /** Debut de la pause en cours (statut PAUSED), null sinon. */
    @Column(name = "paused_at")
    private Instant pausedAt;

    /** Temps total des pauses terminees (ms) - exclu de la duree de travail et du debit. */
    @Builder.Default
    @Column(name = "paused_duration_ms", nullable = false)
    private Long pausedDurationMs = 0L;

    /** Detail des requetes conserve (mode du scenario fige au lancement) ; null = aucun detail. */
    @jakarta.persistence.Enumerated(EnumType.STRING)
    @Column(name = "request_detail_mode", length = 30)
    private com.loadpilot.backend.enums.RequestDetailMode requestDetailMode;

    /** Plafond memoire atteint : l'echantillon des requetes reussies a ete arrete (echecs conserves). */
    @Builder.Default
    @Column(name = "detail_sample_cap_reached", nullable = false)
    private boolean detailSampleCapReached = false;

    /** Chronologie {"vus":[[debut,fin]...],"pauses":[[debut,fin]...]} (epoch ms) ; null = execution anterieure. */
    @Column(name = "timeline_json", length = 100000)
    private String timelineJson;

    /** CPU / RAM du processus de l'application testee {"source","intervalSeconds","samples":[[epochMs,cpu,ramMo]]} ; null sans monitoring. */
    @Column(name = "resource_json", length = 200000)
    private String resourceJson;

    /** CPU moyen et maximal du processus teste, en % d'un coeur (100 % = un coeur entierement occupe). */
    @Column(name = "cpu_avg_percent")
    private Double cpuAvgPercent;

    @Column(name = "cpu_max_percent")
    private Double cpuMaxPercent;

    /** RAM maximale du processus teste (Mo). */
    @Column(name = "memory_max_mb")
    private Double memoryMaxMb;

    /** Message si le monitoring n'a pas (ou pas toujours) repondu ; l'execution continue normalement. */
    @Column(name = "monitoring_message", length = 1000)
    private String monitoringMessage;

    /** Resume calcule une fois l'execution terminee (voir ExecutionStatsService) : P95 global (ms). */
    @Column(name = "p95_ms")
    private Long p95Ms;

    /** Debit (req/s) hors pauses, meme calcul que le rapport. */
    @Column(name = "throughput_rps")
    private Double throughputRps;

    /** P95 par etape : [{"name","method","p95","total"}]. */
    @Column(name = "step_stats_json", length = 50000)
    private String stepStatsJson;

    /** Date du calcul du resume ; null = pas encore calcule. */
    @Column(name = "stats_computed_at")
    private Instant statsComputedAt;

    /** Utilisateur qui a lance l'execution, ou proprietaire de la planification ; null pour les anciennes executions. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triggered_by_app_user_id", updatable = false)
    private AppUser triggeredBy;

    /** Seuils de reussite copies du scenario au lancement (null = execution anterieure aux seuils). */
    @Column(name = "max_error_rate_percent", updatable = false)
    private Double maxErrorRatePercent;

    @Column(name = "max_p95_ms", updatable = false)
    private Long maxP95Ms;

    /** Utilisateur qui a annule l'execution (null = pas annulee, ou annulation automatique). */
    @Column(name = "cancelled_by")
    private String cancelledBy;

    /** Planification a l'origine de cette execution (null = lancement manuel) ; simple id, sans cle etrangere. */
    @Column(name = "schedule_id", updatable = false)
    private UUID scheduleId;

    /** Option « Conserver » : resultats et detail des requetes jamais purges. */
    @Builder.Default
    @Column(name = "keep_results", nullable = false)
    private boolean keepResults = false;

    /** Date de suppression des resultats (une ligne par requete) ; null = encore presents. */
    @Column(name = "results_purged_at")
    private Instant resultsPurgedAt;

    /** Date de suppression des en-tetes / corps des requetes ; null = encore presents. */
    @Column(name = "request_details_purged_at")
    private Instant requestDetailsPurgedAt;

    /** Mode de charge copie au lancement. */
    @Enumerated(EnumType.STRING)
    @Column(name = "load_mode", nullable = false, length = 20)
    @Builder.Default
    private com.loadpilot.backend.enums.LoadMode loadMode = com.loadpilot.backend.enums.LoadMode.MANUAL;

    /** Serie d'essais de stress de l'execution (null hors serie) et rang de l'essai (1..n). */
    @Column(name = "series_id", updatable = false)
    private UUID seriesId;

    @Column(name = "series_index", updatable = false)
    private Integer seriesIndex;

    /** Mode stress : vitesse de montee utilisee (req/s gagnees chaque seconde), copiee au lancement ; null en mode manuel. */
    @Column(name = "stress_ramp_step")
    private Integer stressRampStep;

    // ---- Resultat d'un test de stress (null en mode manuel), garde apres la purge des resultats ----

    @Enumerated(EnumType.STRING)
    @Column(name = "stress_outcome", length = 30)
    private com.loadpilot.backend.enums.StressOutcome stressOutcome;

    /** Aucune rupture : limite de LoadPilot atteinte (TARGET_RPS, VIRTUAL_USERS, DURATION). */
    @Column(name = "stress_limit", length = 20)
    private String stressLimit;

    @Column(name = "stress_message", length = 1000)
    private String stressMessage;

    @Column(name = "stress_peak_virtual_users")
    private Integer stressPeakVirtualUsers;

    @Column(name = "stress_peak_offered_rps")
    private Double stressPeakOfferedRps;

    /** Criteres confirmes, separes par des virgules (RESPONSE_TIME, REFUSALS, THROUGHPUT). */
    @Column(name = "breakpoint_criteria", length = 100)
    private String breakpointCriteria;

    /** Debut de l'anomalie et confirmation, en secondes depuis le debut du test. */
    @Column(name = "breakpoint_onset_seconds")
    private Integer breakpointOnsetSeconds;

    @Column(name = "breakpoint_detected_seconds")
    private Integer breakpointDetectedSeconds;

    /** Niveau de charge et mesures de la premiere fenetre anormale. */
    @Column(name = "breakpoint_virtual_users")
    private Integer breakpointVirtualUsers;

    @Column(name = "breakpoint_offered_rps")
    private Double breakpointOfferedRps;

    @Column(name = "breakpoint_throughput_rps")
    private Double breakpointThroughputRps;

    @Column(name = "breakpoint_p95_ms")
    private Long breakpointP95Ms;

    @Column(name = "baseline_p95_ms")
    private Long baselineP95Ms;

    @Column(name = "breakpoint_refusal_percent")
    private Double breakpointRefusalPercent;

    @Builder.Default
    @OneToMany(mappedBy = "execution", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExecutionStepResult> results = new ArrayList<>();

    /** Duree de travail reelle (ms) : duree totale moins le temps passe en pause ; null si non terminee. */
    public Long activeDurationMs() {
        if (duration == null) return null;
        long paused = pausedDurationMs == null ? 0L : pausedDurationMs;
        return Math.max(0L, duration - paused);
    }
}
