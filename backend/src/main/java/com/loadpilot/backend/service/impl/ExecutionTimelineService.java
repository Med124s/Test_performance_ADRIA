package com.loadpilot.backend.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.ExecutionTimelineResponse;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.loadpilot.backend.service.report.TimelineAnalyzer;
import com.loadpilot.backend.service.report.TimelineAnalyzer.Interval;
import com.loadpilot.backend.service.report.TimelineAnalyzer.Sample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Analyse dans le temps d'une execution (voir TimelineAnalyzer pour le calcul). Lecture des seules
 * colonnes utiles (une requete), jamais d'envoi des requetes au navigateur. Le resultat d'une
 * execution TERMINEE (et completement enregistree) est garde en cache memoire : il ne change plus.
 */
@Service
@RequiredArgsConstructor
public class ExecutionTimelineService {

    private static final int CACHE_SIZE = 100;

    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository resultRepository;
    private final RunningExecutionRegistry registry;
    private final ObjectMapper objectMapper;

    private final Map<UUID, ExecutionTimelineResponse> cache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, ExecutionTimelineResponse> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    @Transactional(readOnly = true)
    public ExecutionTimelineResponse timeline(UUID executionId) {
        // Existence verifiee d'abord (lecture par cle) : une execution supprimee n'est jamais servie depuis le cache.
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> { cache.remove(executionId); return new ResourceNotFoundException("Execution introuvable : " + executionId); });
        ExecutionTimelineResponse cached = cache.get(executionId);
        if (cached != null) return cached;

        List<Sample> samples = new ArrayList<>();
        for (Object[] row : resultRepository.findTimelineRows(executionId)) {
            Instant end = (Instant) row[0];
            if (end == null) continue;
            UUID stepId = (UUID) row[5];
            String name = row[6] != null ? (String) row[6] : row[7] != null ? (String) row[7] : "Étape supprimée";
            Object method = row[8] != null ? row[8] : row[9];
            samples.add(new Sample(end.toEpochMilli(), row[1] == null ? 0L : (Long) row[1], Boolean.TRUE.equals(row[2]),
                    (Integer) row[3], (String) row[4], stepId != null ? stepId.toString() : "deleted:" + name, name,
                    method != null ? method.toString() : null, (Integer) row[10]));
        }

        List<Interval> vus = null;
        List<Interval> pauses = List.of();
        if (execution.getTimelineJson() != null) {
            try {
                Map<String, List<long[]>> json = objectMapper.readValue(execution.getTimelineJson(),
                        new TypeReference<Map<String, List<long[]>>>() { });
                vus = toIntervals(json.get("vus"));
                pauses = toIntervals(json.get("pauses"));
            } catch (Exception ignored) {
                // Chronologie illisible : utilisateurs actifs deduits des requetes.
            }
        }

        ExecutionTimelineResponse response = TimelineAnalyzer.analyze(samples, vus, pauses, resources(execution));
        // Cache : execution terminee ET tache asynchrone finie (resultats + chronologie enregistres).
        if (!execution.getStatus().isActive() && registry.get(executionId) == null) {
            cache.put(executionId, response);
        }
        return response;
    }

    /** Mesures CPU / RAM enregistrees (voir ExecutionTransactionHelper.saveResources) ; null sans monitoring. */
    private TimelineAnalyzer.Resources resources(Execution execution) {
        if (execution.getResourceJson() == null) {
            return execution.getMonitoringMessage() == null ? null
                    : new TimelineAnalyzer.Resources(null, 0, null, execution.getMonitoringMessage(), List.of());
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(execution.getResourceJson());
            List<TimelineAnalyzer.ResourcePoint> points = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode s : root.path("samples")) {
                points.add(new TimelineAnalyzer.ResourcePoint(s.get(0).asLong(),
                        s.get(1) == null || s.get(1).isNull() ? null : s.get(1).asDouble(),
                        s.get(2) == null || s.get(2).isNull() ? null : s.get(2).asDouble()));
            }
            return new TimelineAnalyzer.Resources(root.path("source").asText(null), root.path("intervalSeconds").asInt(0),
                    root.path("memoryMetric").isNull() ? null : root.path("memoryMetric").asText(null),
                    execution.getMonitoringMessage(), points);
        } catch (Exception e) {
            return null; // mesures illisibles : la courbe CPU / RAM n'est simplement pas affichee
        }
    }

    private static List<Interval> toIntervals(List<long[]> raw) {
        List<Interval> out = new ArrayList<>();
        if (raw != null) for (long[] r : raw) if (r != null && r.length == 2) out.add(new Interval(r[0], r[1]));
        return out;
    }
}
