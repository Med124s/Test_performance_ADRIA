package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.ExecutionExtremesResponse;
import com.loadpilot.backend.dto.response.ExecutionExtremesResponse.ExtremeRequest;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D'ou viennent les cartes Minimum / Maximum du rapport : les LIMIT requetes les plus rapides ou
 * les plus lentes, lues dans la base via l'index (execution_id, response_time_ms) - jamais toutes
 * les requetes en memoire. Ordre : temps de reponse, puis la plus ancienne d'abord ; la premiere
 * est donc exactement la valeur de la carte (meme population : requetes au temps mesure).
 *
 * Contexte « au meme moment » : intervalle de l'analyse dans le temps (deja calculee et mise en
 * cache pour une execution terminee) contenant la fin de la requete.
 */
@Service
@RequiredArgsConstructor
public class ExecutionExtremesService {

    public static final int LIMIT = 10;

    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository resultRepository;
    private final ExecutionRequestDetailService requestDetailService;
    private final ExecutionTimelineService timelineService;
    private final ReportSnapshots reportSnapshots;

    @Transactional(readOnly = true)
    public ExecutionExtremesResponse extremes(UUID executionId, boolean slowest) {
        com.loadpilot.backend.entity.Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Exécution introuvable : " + executionId));
        String kind = slowest ? "MAX" : "MIN";
        // Detail des requetes supprime : requetes extremes du rapport fige (sans en-tetes ni corps).
        if (ReportSnapshots.purged(execution)) {
            ExecutionExtremesResponse frozen = reportSnapshots.read(execution)
                    .map(s -> slowest ? s.slowest() : s.fastest()).orElse(null);
            return frozen != null ? ReportSnapshots.withoutDetail(frozen) : new ExecutionExtremesResponse(kind, null, 0, 1, List.of());
        }
        List<ExecutionStepResult> results = slowest
                ? resultRepository.findSlowest(executionId, PageRequest.of(0, LIMIT))
                : resultRepository.findFastest(executionId, PageRequest.of(0, LIMIT));
        if (results.isEmpty()) {
            return new ExecutionExtremesResponse(kind, null, 0, 1, List.of());
        }
        ExecutionTimelineResponse tl = timelineService.timeline(executionId);
        int bucketSeconds = Math.max(1, tl.bucketSeconds());
        Map<UUID, String> kinds = requestDetailService.detailKinds(results);
        List<ExtremeRequest> out = new ArrayList<>(results.size());
        for (ExecutionStepResult r : results) {
            long offsetMs = r.getTimestamp() == null ? 0 : r.getTimestamp().toEpochMilli() - tl.startEpochMs();
            int b = tl.t().isEmpty() ? -1 : (int) Math.min(tl.t().size() - 1, Math.max(0, offsetMs / (bucketSeconds * 1000L)));
            out.add(new ExtremeRequest(requestDetailService.row(r, kinds.get(r.getId())), Math.round(offsetMs / 100.0) / 10.0,
                    b < 0 ? 0 : tl.t().get(b),
                    b < 0 ? null : tl.activeVirtualUsers().get(b),
                    b < 0 ? null : tl.throughput().get(b),
                    b < 0 ? null : tl.p95Ms().get(b)));
        }
        return new ExecutionExtremesResponse(kind, results.get(0).getResponseTime(), resultRepository.countMeasured(executionId),
                bucketSeconds, out);
    }
}
