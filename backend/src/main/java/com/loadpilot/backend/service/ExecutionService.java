package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.ExecutionHistoryFilterRequest;
import com.loadpilot.backend.dto.request.ExecutionRequest;
import com.loadpilot.backend.dto.response.ExecutionDetailResponse;
import com.loadpilot.backend.dto.response.ExecutionHistoryResponse;
import com.loadpilot.backend.dto.response.ExecutionReportResponse;
import com.loadpilot.backend.dto.response.ExecutionResponse;
import com.loadpilot.backend.dto.response.ExecutionStatusResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

public interface ExecutionService {

    /**
     * Cree l'Execution (QUEUED), planifie reellement son execution
     * asynchrone (P0-A - ne bloque plus jusqu'a la fin), et retourne
     * immediatement l'etat QUEUED. Voir getStatus/getById pour suivre.
     *
     * "triggeredByAppUserId" (P1-B) : id AppUser reellement a l'origine de
     * ce lancement (voir ExecutionController, qui le resout depuis le Jwt
     * de la requete via AppUserSyncService) - fige sur l'Execution creee
     * (voir ExecutionTransactionHelper.prepareAndStart), sert UNIQUEMENT a
     * determiner le destinataire de la Notification EXECUTION_* emise a la
     * fin (voir Execution.triggeredBy). Peut valoir null (jamais fabrique).
     * Un UUID brut (jamais une entite AppUser) traverse volontairement
     * cette frontiere de service : une entite chargee dans UNE transaction
     * ne doit jamais etre reutilisee comme reference geree dans une AUTRE
     * transaction potentiellement bien plus tardive (voir
     * ScheduledExecutionPoller, qui declenche ce chemin bien apres avoir lu
     * la ScheduledExecution) - seule la reference (getReferenceById) est
     * reconstruite, la ou elle est reellement utilisee, dans SA propre
     * transaction.
     */
    ExecutionResponse execute(ExecutionRequest request, UUID triggeredByAppUserId);

    /**
     * P1-B — UNIQUEMENT pour ScheduledExecutionPoller : declenche le MEME
     * chemin exactement que execute() (jamais un moteur duplique), avec
     * "triggeredByAppUserId" = le proprietaire de la ScheduledExecution et
     * "scheduleId" trace uniquement pour le message de la Notification
     * emise a la fin (voir ExecutionServiceImpl.runAsync) - jamais persiste
     * sur l'Execution elle-meme (aucun besoin demontre au-dela de ce
     * message).
     */
    ExecutionResponse executeScheduled(UUID scenarioId, UUID triggeredByAppUserId, UUID scheduleId);

    ExecutionDetailResponse getById(UUID id);

    /** Vue allegee pour du polling frequent (P0-A) - voir ExecutionStatusResponse. */
    ExecutionStatusResponse getStatus(UUID id);

    List<ExecutionResponse> list();

    /** Leve ResourceNotFoundException si le scenario n'existe pas. */
    List<ExecutionResponse> listByScenario(UUID scenarioId);

    /** Annulation reelle (P0-A) : voir l'implementation pour le detail par statut. */
    ExecutionResponse cancel(UUID id);

    /** Cree une NOUVELLE Execution QUEUED du meme scenario - ne modifie jamais l'ancienne. */
    ExecutionResponse retry(UUID id, UUID triggeredByAppUserId);

    /**
     * P1-A — historique paginable/filtrable/triable, filtrage et tri
     * executes cote base (voir ExecutionSpecifications), jamais en memoire.
     * "sort" est deja valide/traduit en un Sort JPA sur au plus les colonnes
     * autorisees AVANT d'atteindre cette methode (voir ExecutionController)
     * - jamais un nom de colonne fourni par le client passe tel quel.
     */
    PagedResponse<ExecutionHistoryResponse> getHistory(
            ExecutionHistoryFilterRequest filter, int page, int size, Sort sort);

    /** P1-A — rapport statistique complet (percentiles reels, agregation
     * par step, erreurs) - voir ExecutionReportResponse. */
    ExecutionReportResponse getReport(UUID id);

    /** P1-A — export CSV du meme rapport (memes autorisations que la
     * consultation, voir ExecutionController). */
    String exportReportCsv(UUID id);
}
