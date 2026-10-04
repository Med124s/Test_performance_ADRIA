package com.loadpilot.backend.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.ExecutionRequestDetailResponse;
import com.loadpilot.backend.dto.response.ExecutionRequestRowResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.entity.ExecutionRequestDetail;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ExecutionRequestDetailRepository;
import com.loadpilot.backend.repository.ExecutionStepResultRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture des requetes d'une execution : liste paginee cote base (filtres etape, utilisateur
 * virtuel, iteration, echecs seulement) et detail complet d'une requete (si conserve).
 */
@Service
@RequiredArgsConstructor
public class ExecutionRequestDetailService {

    public static final String DETAIL_NOT_KEPT =
            "Le détail de cette requête n'a pas été conservé (seuls les échecs et un échantillon des requêtes réussies le sont).";

    private static final Sort CHRONOLOGICAL = Sort.by(Sort.Direction.ASC, "timestamp").and(Sort.by("virtualUser"));

    private final ExecutionRepository executionRepository;
    private final ExecutionStepResultRepository resultRepository;
    private final ExecutionRequestDetailRepository detailRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PagedResponse<ExecutionRequestRowResponse> list(UUID executionId, UUID stepId, String stepName, Integer virtualUser,
            Integer iteration, boolean failedOnly, int page, int size) {
        if (!executionRepository.existsById(executionId)) {
            throw new ResourceNotFoundException("Exécution introuvable : " + executionId);
        }
        Specification<ExecutionStepResult> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("execution").get("id"), executionId));
            if (stepId != null) {
                p.add(cb.equal(root.get("step").get("id"), stepId));
            } else if (stepName != null && !stepName.isBlank()) {
                // Etape supprimee depuis : retrouvee par sa copie (nom), step = null.
                p.add(cb.isNull(root.get("step")));
                p.add(cb.equal(root.get("stepName"), stepName));
            }
            if (virtualUser != null) p.add(cb.equal(root.get("virtualUser"), virtualUser));
            if (iteration != null) p.add(cb.equal(root.get("iteration"), iteration));
            if (failedOnly) p.add(cb.isFalse(root.get("success")));
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<ExecutionStepResult> rows = resultRepository.findAll(spec, PageRequest.of(page, size, CHRONOLOGICAL));
        Map<UUID, String> kinds = new HashMap<>();
        if (!rows.isEmpty()) {
            for (Object[] row : detailRepository.findKindsByResultIds(rows.getContent().stream().map(ExecutionStepResult::getId).toList())) {
                kinds.put((UUID) row[0], (String) row[1]);
            }
        }
        return new PagedResponse<>(rows.getContent().stream().map(r -> toRow(r, kinds.get(r.getId()))).toList(),
                rows.getNumber(), rows.getSize(), rows.getTotalElements(), rows.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ExecutionRequestDetailResponse detail(UUID executionId, UUID resultId) {
        ExecutionStepResult result = resultRepository.findById(resultId)
                .filter(r -> r.getExecution().getId().equals(executionId))
                .orElseThrow(() -> new ResourceNotFoundException("Requête introuvable dans cette exécution."));
        ExecutionRequestDetail d = detailRepository.findByResultId(resultId)
                .orElseThrow(() -> new ResourceNotFoundException(DETAIL_NOT_KEPT));
        ExecutionRequestDetailResponse.Checks checks = d.getChecks() == null ? null : read(d.getChecks(), new TypeReference<ExecutionRequestDetailResponse.Checks>() { });
        return new ExecutionRequestDetailResponse(
                toRow(result, d.getKind()),
                d.getKind(),
                new ExecutionRequestDetailResponse.Request(d.getRequestMethod(), d.getRequestUrl(), headers(d.getRequestHeaders()),
                        d.getRequestBody(), d.isRequestBodyTruncated()),
                d.getResponseStatus() == null && d.getResponseHeaders() == null ? null
                        : new ExecutionRequestDetailResponse.Response(d.getResponseStatus(), result.getResponseTime(),
                                d.getResponseSizeBytes(), headers(d.getResponseHeaders()), d.getResponseBody(), d.isResponseBodyTruncated()),
                checks);
    }

    private ExecutionRequestRowResponse toRow(ExecutionStepResult r, String detailKind) {
        UUID stepId = r.stepIdOrNull();
        return new ExecutionRequestRowResponse(r.getId().toString(), stepId != null ? stepId.toString() : null,
                r.displayStepName(), r.displayStepMethod(), r.getVirtualUser(), r.getIteration(), r.getTimestamp(),
                r.getHttpStatus(), r.getResponseTime(), r.getSuccess(), r.getError(), r.getRequestUrl(), detailKind);
    }

    private Map<String, String> headers(String json) {
        return json == null ? null : read(json, new TypeReference<java.util.LinkedHashMap<String, String>>() { });
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }
}
