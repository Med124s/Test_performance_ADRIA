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

    /** Cree l'execution (QUEUED), lance son deroulement en arriere-plan et la renvoie aussitot. */
    ExecutionResponse execute(ExecutionRequest request, UUID triggeredByAppUserId);

    /** Lancement d'une execution par une planification. */
    ExecutionResponse executeScheduled(UUID scenarioId, UUID triggeredByAppUserId, UUID scheduleId);

    ExecutionDetailResponse getById(UUID id);

    /** includeResults = false : sans la liste des requetes. */
    ExecutionDetailResponse getById(UUID id, boolean includeResults);

    /** Etat d'une execution pour le suivi en direct. */
    ExecutionStatusResponse getStatus(UUID id);

    List<ExecutionResponse> list();

    /** Leve ResourceNotFoundException si le scenario n'existe pas. */
    List<ExecutionResponse> listByScenario(UUID scenarioId);

    /** Annule une execution en attente, en cours ou en pause. */
    ExecutionResponse cancel(UUID id);

    ExecutionResponse pause(UUID id);

    ExecutionResponse resume(UUID id);

    /** Cree une nouvelle execution du meme scenario. */
    ExecutionResponse retry(UUID id, UUID triggeredByAppUserId);

    /** Historique pagine, filtre et trie. */
    PagedResponse<ExecutionHistoryResponse> getHistory(
            ExecutionHistoryFilterRequest filter, int page, int size, Sort sort);

    com.loadpilot.backend.dto.response.ExecutionPageResponse getPage(
            ExecutionHistoryFilterRequest filter, int page, int size, Sort sort);

    ExecutionReportResponse getReport(UUID id);
}
