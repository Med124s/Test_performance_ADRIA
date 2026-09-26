package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * P1-A : etend JpaSpecificationExecutor (meme pattern deja etabli par
 * AuditLogRepository) pour l'historique filtrable/paginable/triable (voir
 * specification.ExecutionSpecifications et controller.ExecutionController
 * #history) - jamais une seconde mecanique de pagination differente.
 */
public interface ExecutionRepository extends JpaRepository<Execution, UUID>, JpaSpecificationExecutor<Execution> {

    List<Execution> findByScenarioId(UUID scenarioId, Sort sort);

    /**
     * Charge l'Execution avec son Scenario et l'Application de ce Scenario
     * deja initialises (JOIN FETCH) - evite tout risque de
     * LazyInitializationException lors de la construction des reponses
     * (scenarioName, resolution d'URL) une fois hors transaction.
     *
     * P1-C : LEFT JOIN FETCH sur "triggeredBy" (nullable - voir
     * Execution.triggeredBy, P1-B) pour exposer triggeredByUsername sur
     * ExecutionHistoryResponse/ExecutionReportResponse sans requete
     * supplementaire - LEFT (jamais INNER) car une Execution anterieure a
     * P1-B a triggeredBy = null et ne doit jamais disparaitre des resultats.
     */
    @Query("SELECT e FROM Execution e JOIN FETCH e.scenario s JOIN FETCH s.application LEFT JOIN FETCH e.triggeredBy WHERE e.id = :id")
    Optional<Execution> findByIdWithScenarioAndApplication(@Param("id") UUID id);

    /**
     * P0-B (voir ExecutionTransactionHelper.attemptCancellation/markRunning/
     * finalizeExecution) : verrou pessimiste (SELECT ... FOR UPDATE) sur la
     * ligne Execution, SANS jointure - serialise les ecritures concurrentes
     * de statut sur UNE MEME execution (cancel(), demarrage reel du moteur,
     * finalisation) pour eliminer une vraie course identifiee (un cancel()
     * ayant lu un statut QUEUED desormais perime pouvait ecraser un SUCCESS/
     * FAILED deja atteint naturellement entre-temps). Verrou au niveau d'UNE
     * seule ligne d'une seule base - jamais un verrou distribue.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Execution e WHERE e.id = :id")
    Optional<Execution> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Meme verrou que findByIdForUpdate, mais avec Scenario/Application deja
     * initialises (voir findByIdWithScenarioAndApplication) - necessaire
     * pour ExecutionTransactionHelper.attemptCancellation, dont le resultat
     * est mappe en reponse HTTP APRES la fin de la transaction (donc apres
     * la fermeture de la session Hibernate) : sans ce chargement eager,
     * cette lecture-la produirait une LazyInitializationException.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Execution e JOIN FETCH e.scenario s JOIN FETCH s.application WHERE e.id = :id")
    Optional<Execution> findByIdWithScenarioAndApplicationForUpdate(@Param("id") UUID id);

    /**
     * P0-B (voir ExecutionRecoveryRunner) : toutes les executions encore
     * QUEUED/RUNNING en base au demarrage du backend sont necessairement
     * orphelines (le registre en memoire qui les pilotait a disparu avec
     * l'ancien processus) - jamais une supposition, une consequence directe
     * de RunningExecutionRegistry etant purement en memoire.
     */
    List<Execution> findByStatusIn(Collection<ExecutionStatus> statuses);

    // ---- Phase 11 (Dashboard) - agregations par statut/Application, une
    // seule requete COUNT chacune (jamais de N+1). ----

    long countByStatus(ExecutionStatus status);

    @Query("SELECT COUNT(e) FROM Execution e WHERE e.scenario.application.id = :applicationId")
    long countByApplicationId(@Param("applicationId") UUID applicationId);

    @Query("SELECT COUNT(e) FROM Execution e WHERE e.scenario.application.id = :applicationId AND e.status = :status")
    long countByApplicationIdAndStatus(@Param("applicationId") UUID applicationId, @Param("status") ExecutionStatus status);

    // ---- P1-C - Dashboard enrichi (plage temporelle 24h/7j/30j/personnalisee,
    // voir DashboardServiceImpl) - "from"/"to" sont TOUJOURS non-null au
    // moment de l'appel (resolus par le service : from = Instant.EPOCH si
    // absent, to = Instant.now() si absent) - jamais de gestion de null
    // dans ces requetes, jamais de N+1 (une seule requete d'agregation
    // chacune). ----

    long countByStartedAtBetween(Instant from, Instant to);

    long countByStatusAndStartedAtBetween(ExecutionStatus status, Instant from, Instant to);

    /**
     * Top N scenarios par nombre d'executions REELLEMENT lancees dans la
     * plage - une seule requete groupee (jamais un chargement de toute la
     * table Execution suivi d'un comptage en Java, voir prompt P1-C section
     * 12 : "ne jamais charger 10000 lignes memoire"). "successCount" permet
     * au service de calculer un taux de reussite reel sans requete
     * supplementaire.
     */
    @Query("SELECT sc.id AS scenarioId, sc.name AS scenarioName, app.name AS applicationName, "
            + "COUNT(e) AS executionCount, "
            + "SUM(CASE WHEN e.status = :successStatus THEN 1L ELSE 0L END) AS successCount "
            + "FROM Execution e JOIN e.scenario sc JOIN sc.application app "
            + "WHERE e.startedAt BETWEEN :from AND :to "
            + "GROUP BY sc.id, sc.name, app.name "
            + "ORDER BY COUNT(e) DESC")
    List<ScenarioExecutionCountProjection> findTopScenariosByExecutionCount(
            @Param("from") Instant from, @Param("to") Instant to,
            @Param("successStatus") ExecutionStatus successStatus, Pageable pageable);
}
