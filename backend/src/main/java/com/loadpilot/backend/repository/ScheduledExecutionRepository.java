package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.ScheduledExecution;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduledExecutionRepository extends JpaRepository<ScheduledExecution, UUID> {

    /** JOIN FETCH scenario/application/createdBy - evite un N+1 sur toute la
     * liste (meme raison que findByIdWithScenarioAndApplication ci-dessous). */
    @Query("SELECT s FROM ScheduledExecution s JOIN FETCH s.scenario sc JOIN FETCH sc.application JOIN FETCH s.createdBy ORDER BY s.createdAt DESC")
    List<ScheduledExecution> findAllWithScenarioAndApplication();

    @Query("SELECT s FROM ScheduledExecution s JOIN FETCH s.scenario sc JOIN FETCH sc.application JOIN FETCH s.createdBy WHERE sc.id = :scenarioId ORDER BY s.createdAt DESC")
    List<ScheduledExecution> findByScenarioIdWithScenarioAndApplication(@Param("scenarioId") UUID scenarioId);

    /**
     * JOIN FETCH scenario/application ET createdBy - necessaire car cette
     * methode est aussi appelee depuis runNow() (volontairement SANS
     * @Transactional propre, voir ScheduledExecutionServiceImpl), ou la
     * transaction courte de CETTE requete Spring Data serait deja fermee au
     * moment ou ScheduledExecutionMapper accede a "createdBy" (via
     * MapperSupport.appUserDisplayName) - sans ce chargement eager, ce
     * serait une LazyInitializationException reelle, pas hypothetique.
     */
    @Query("SELECT s FROM ScheduledExecution s JOIN FETCH s.scenario sc JOIN FETCH sc.application JOIN FETCH s.createdBy WHERE s.id = :id")
    Optional<ScheduledExecution> findByIdWithScenarioAndApplication(UUID id);

    /**
     * Lecture LEGERE (pas de verrou) executee toutes les
     * app.scheduler.poll-interval-ms par ScheduledExecutionPoller - ne
     * retourne QUE les identifiants (jamais l'entite complete) : la vraie
     * revalidation (encore due ? toujours activee ?) et la reclamation
     * atomique se font ensuite UNE PAR UNE, sous verrou pessimiste, via
     * findByIdForUpdate (voir ScheduledExecutionTransactionHelper) - jamais
     * de decision de declenchement prise depuis cette lecture non verrouillee.
     */
    @Query("SELECT s.id FROM ScheduledExecution s WHERE s.enabled = true AND s.nextRunAt IS NOT NULL AND s.nextRunAt <= :now")
    List<UUID> findDueScheduleIds(@Param("now") Instant now);

    /**
     * Verrou pessimiste (SELECT ... FOR UPDATE) sur une seule ligne - meme
     * idiome que ExecutionRepository#findByIdForUpdate : garantit qu'un
     * declenchement automatique (poller) et un declenchement manuel
     * ("run now") sur la MEME ScheduledExecution ne peuvent jamais tous les
     * deux reussir a la reclamer simultanement (protection reelle anti
     * double-declenchement, transactionnelle - jamais un simple booleen en
     * memoire).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ScheduledExecution s WHERE s.id = :id")
    Optional<ScheduledExecution> findByIdForUpdate(@Param("id") UUID id);
}
