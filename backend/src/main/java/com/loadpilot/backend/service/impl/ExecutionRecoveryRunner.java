package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.NotificationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * P0-B — strategie reelle de recuperation au demarrage (section 5 du
 * prompt P0-B).
 *
 * PROBLEME : RunningExecutionRegistry est purement en memoire (voir sa
 * Javadoc, deja documente en P0-A). Une Execution QUEUED/RUNNING au moment
 * ou le processus backend s'arrete (crash, redemarrage, deploiement) reste
 * ecrite ainsi en base indefiniment : plus aucun thread reel n'existe pour
 * la faire progresser, plus aucune RunningExecutionHandle ne permet de
 * l'annuler proprement via l'API (elle repondrait 409 "aucune execution
 * active trouvee en memoire"), et le frontend continuerait a l'afficher
 * comme "En cours" pour toujours.
 *
 * OPTIONS :
 * (a) Ne rien faire - l'Execution reste RUNNING pour toujours (etat
 *     mensonger, viole "Une execution persistee en base ne doit jamais
 *     rester indefiniment RUNNING").
 * (b) Tenter de RE-EXECUTER reellement le Scenario depuis le debut au
 *     redemarrage - une "reprise" qui n'en est pas une : les VUs deja
 *     executes avant le crash ne sont jamais reellement connus (voir plus
 *     bas, aucun ExecutionStepResult n'est jamais persiste avant la toute
 *     fin d'une Execution - HttpClientExecutionEngine accumule tout en
 *     memoire), donc "reprendre exactement la ou elle etait" est
 *     techniquement IMPOSSIBLE avec l'architecture actuelle. Le prompt
 *     interdit explicitement ce "fake resume".
 * (c) Terminer proprement chaque Execution orpheline en FAILED, avec une
 *     raison explicite, au tout premier demarrage qui la trouve encore
 *     QUEUED/RUNNING.
 *
 * CHOIX : (c). JUSTIFICATION : c'est la seule option qui ne mente jamais
 * sur l'etat reel du systeme. Constat verifie dans le code (pas suppose) :
 * ExecutionTransactionHelper.finalizeExecution est le SEUL endroit qui
 * persiste des ExecutionStepResult, et il n'est appele qu'une fois, a la
 * toute fin d'une Execution (succes, echec ou annulation) - donc une
 * Execution interrompue par un crash n'a JAMAIS de resultat partiel
 * persiste a preserver ou a partir duquel reprendre. FAILED (jamais
 * CANCELLED : personne n'a demande l'annulation - c'est bien un echec reel
 * de l'infrastructure, pas une decision utilisateur) avec le message exact
 * "Backend restarted while execution was running" (demande explicitement
 * par le prompt).
 *
 * IDEMPOTENCE : cette methode ne modifie que les Executions encore
 * QUEUED/RUNNING - une fois passees a FAILED, une execution ulterieure de
 * cette meme methode (redemarrage suivant, ou meme run() rappele deux fois
 * par erreur) ne les retrouve plus jamais (requete WHERE status IN
 * (QUEUED,RUNNING)) : naturellement idempotent, aucune deduplication
 * supplementaire necessaire.
 *
 * PLUSIEURS INSTANCES : hors scope, deployment mono-instance assume dans
 * tout ce backend a ce stade (voir RunningExecutionRegistry) - jamais de
 * verrou distribue introduit ici sans un besoin reel demontre.
 */
@Component
@RequiredArgsConstructor
public class ExecutionRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecoveryRunner.class);
    private static final String RECOVERY_REASON = "Backend restarted while execution was running";

    private final ExecutionRepository executionRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Execution> orphaned = executionRepository.findByStatusIn(
                List.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING));

        if (orphaned.isEmpty()) {
            log.info("Recuperation au demarrage : aucune execution orpheline (QUEUED/RUNNING) trouvee.");
            return;
        }

        Instant now = Instant.now();
        for (Execution execution : orphaned) {
            execution.setStatus(ExecutionStatus.FAILED);
            execution.setErrorMessage(RECOVERY_REASON);
            execution.setFinishedAt(now);
            execution.setDuration(Duration.between(execution.getStartedAt(), now).toMillis());
            executionRepository.save(execution);

            auditLogService.record(AuditAction.EXECUTE, AuditModule.EXECUTION, AuditResult.FAILURE,
                    "Execution " + execution.getId() + " recovered as FAILED after backend restart");

            // P1-B - notifie reellement l'utilisateur a l'origine. Seul
            // getId() est appele sur le proxy lazy triggeredBy (jamais une
            // autre methode) : un id de proxy Hibernate est toujours connu
            // sans initialisation, donc jamais de LazyInitializationException
            // meme si ce proxy etait manipule hors de cette transaction -
            // ici il ne l'est de toute facon pas (voir Execution.triggeredBy).
            // null pour toute Execution anterieure a la colonne
            // triggered_by_app_user_id, auquel cas NotificationService.create
            // n'emet simplement rien.
            UUID triggeredByAppUserId = execution.getTriggeredBy() != null ? execution.getTriggeredBy().getId() : null;
            notificationService.create(triggeredByAppUserId, NotificationType.EXECUTION_FAILED,
                    "Exécution échouée (redémarrage serveur)",
                    "L'exécution " + execution.getId() + " a été marquée en échec car le serveur a redémarré pendant son déroulement.",
                    execution.getId(), null);
        }

        log.warn("Recuperation au demarrage : {} execution(s) orpheline(s) marquee(s) FAILED ({}).",
                orphaned.size(), RECOVERY_REASON);
    }
}
