package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.service.execution.TriggerClaim;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * P1-B — declenchement AUTOMATIQUE des ScheduledExecution dues.
 *
 * CHOIX TECHNOLOGIQUE (prompt P1-B, section 30 - comparaison exigee) :
 * un simple poller Spring {@code @Scheduled(fixedDelay)} interrogeant
 * PostgreSQL, PAS Quartz, PAS un {@code TaskScheduler} en memoire :
 *
 * - {@code TaskScheduler}/{@code ScheduledExecutorService} en memoire
 *   (ex: {@code scheduler.schedule(task, triggerTime)}) : REJETE - ne
 *   survit PAS a un redemarrage (les taches planifiees existent
 *   uniquement dans le tas JVM du processus - EXACTEMENT le probleme deja
 *   documente et corrige pour RunningExecutionRegistry via
 *   ExecutionRecoveryRunner, prompt P1-B l'interdit explicitement pour la
 *   planification : "le systeme doit persister ET survivre a un
 *   redemarrage").
 * - Quartz (JobStore JDBC clusterise) : REJETE pour l'instant - resoudrait
 *   deja le multi-instance futur nativement, mais ajoute une dependance
 *   lourde (tables JobStore dediees, serialisation de JobDataMap, API de
 *   configuration significative) pour un besoin actuel strictement
 *   mono-instance. Le poller ci-dessous couvre DEJA la persistance/
 *   redemarrage (l'etat vit dans scheduled_execution, pas en memoire) ; le
 *   SEUL gain reel de Quartz serait le multi-instance, non demontre a ce
 *   jour (RunningExecutionRegistry, dont ce poller depend indirectement
 *   via ExecutionService, est deja lui-meme mono-instance - Quartz seul
 *   ne resoudrait donc qu'une moitie du probleme multi-instance de toute
 *   facon). A RECONSIDERER explicitement si/quand un besoin reel de
 *   scale-out horizontal apparait (voir rapport P1-B).
 * - Poller PostgreSQL + verrou pessimiste (CHOISI) : reutilise l'idiome
 *   DEJA etabli et teste par ExecutionTransactionHelper
 *   (SELECT ... FOR UPDATE), persiste nativement (nextRunAt en base),
 *   survit a un redemarrage sans aucun code de recuperation dedie
 *   (contrairement a l'Execution QUEUED/RUNNING - une ScheduledExecution
 *   n'est JAMAIS "en cours" entre deux poll ticks, il n'y a donc rien a
 *   recuperer), et SELECT...FOR UPDATE reste correct meme si plusieurs
 *   instances backend partagaient un jour la meme base (chacune ne
 *   reclame que les lignes que les autres n'ont pas deja verrouillees/
 *   avancees) - migration vers Quartz seulement si des besoins de
 *   planification plus riches (calendriers d'exclusion, misfire policies
 *   avancees) apparaissent reellement.
 *
 * Intervalle configurable (app.scheduler.poll-interval-ms, defaut 5000ms) -
 * jamais code en dur (meme regle que les limites de capacite P0-B).
 */
@Component
@RequiredArgsConstructor
public class ScheduledExecutionPoller {

    private static final Logger log = LoggerFactory.getLogger(ScheduledExecutionPoller.class);

    private final com.loadpilot.backend.repository.ScheduledExecutionRepository scheduledExecutionRepository;
    private final ScheduledExecutionTransactionHelper transactionHelper;
    private final ScheduledExecutionTrigger trigger;

    @Scheduled(fixedDelayString = "${app.scheduler.poll-interval-ms:5000}")
    public void pollAndTrigger() {
        Instant now = Instant.now();
        List<UUID> dueIds;
        try {
            dueIds = scheduledExecutionRepository.findDueScheduleIds(now);
        } catch (RuntimeException e) {
            // Ex: base temporairement indisponible - un poll tick manque,
            // le suivant reessaiera naturellement (jamais de crash-loop).
            log.warn("Lecture des planifications dues echouee : {}", e.getClass().getSimpleName());
            return;
        }

        for (UUID id : dueIds) {
            try {
                TriggerClaim claim = transactionHelper.claimForAutomaticTrigger(id, now);
                if (!claim.claimed()) {
                    // Deja reclamee par ce meme tick (course theorique) ou
                    // desactivee/modifiee entre-temps - jamais un
                    // declenchement force malgre tout.
                    continue;
                }
                trigger.fire(claim, false);
            } catch (RuntimeException e) {
                // Une planification en echec (capacite atteinte, scenario
                // supprime...) ne doit JAMAIS empecher les autres
                // planifications dues d'etre traitees dans ce meme tick -
                // deja notifiee/auditee par ScheduledExecutionTrigger.fire
                // avant de relever cette exception.
                log.warn("Declenchement automatique de la planification {} echoue : {}", id, e.getClass().getSimpleName());
            }
        }
    }
}
