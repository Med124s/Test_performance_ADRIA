package com.loadpilot.backend.service.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.repository.ScheduledExecutionRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

/**
 * Transforme des entrees du journal en lignes lisibles (libelles francais,
 * phrase, objet concerne et s'il existe encore, avant -> apres). L'existence
 * des objets est verifiee en une requete par type pour toute la page.
 */
@Component
@RequiredArgsConstructor
public class AuditLogPresenter {

    private static final TypeReference<List<AuditChange>> CHANGES = new TypeReference<>() { };

    private final ObjectMapper objectMapper;
    private final ApplicationRepository applicationRepository;
    private final ScenarioRepository scenarioRepository;
    private final ExecutionRepository executionRepository;
    private final ScheduledExecutionRepository scheduledExecutionRepository;

    public List<AuditLogResponse> present(List<AuditLog> rows) {
        Map<AuditTargetType, Set<String>> existing = existingTargets(rows);
        List<AuditLogResponse> result = new ArrayList<>(rows.size());
        for (AuditLog row : rows) {
            result.add(present(row, existing));
        }
        return result;
    }

    public AuditLogResponse present(AuditLog row) {
        return present(List.of(row)).get(0);
    }

    private AuditLogResponse present(AuditLog row, Map<AuditTargetType, Set<String>> existing) {
        AuditTargetType targetType = parseType(row.getTargetType());
        boolean legacy = targetType == null && row.getChanges() == null && "USER".equals(row.getActorType()) && row.getExternalId() == null
                && row.getActorSource() == null && isLegacyText(row.getDescription());
        boolean exists = targetType != null && row.getTargetId() != null
                && (targetType == AuditTargetType.USER || targetType == AuditTargetType.SETTINGS
                    || existing.getOrDefault(targetType, Set.of()).contains(row.getTargetId()));
        AuditCategory category = AuditCategory.of(row.getModule());
        String detail = legacy ? AuditSanitizer.withoutExceptionNames(row.getDescription()) : row.getDescription();
        return new AuditLogResponse(
                row.getId() != null ? row.getId().toString() : null,
                row.getDate(),
                row.getActorType(),
                row.getUserId(),
                row.getUsername(),
                row.getActorSource(),
                row.getAction(),
                AuditLabels.action(row.getAction(), row.getDescription()),
                row.getModule(),
                category.name(),
                category.label(),
                row.getResult(),
                AuditLabels.result(row.getResult()),
                AuditLabels.summary(row.getAction(), row.getModule(), targetType, row.getTargetLabel(), row.getDescription()),
                detail,
                targetType != null ? targetType.name() : null,
                row.getTargetId(),
                row.getTargetLabel(),
                exists,
                readChanges(row.getChanges()),
                row.getIp(),
                AuditLabels.ip(row.getIp()),
                row.getUserAgent(),
                legacy,
                row.getDescription());
    }

    /** Anciennes descriptions (avant le journal enrichi) : en anglais, donc sans aucun caractere accentue. */
    private static boolean isLegacyText(String description) {
        return description != null && !description.matches("(?s).*[éèàùâêîôûçÉÈÀ’«].*");
    }

    public List<AuditChange> readChanges(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, CHANGES);
        } catch (Exception e) {
            return List.of();
        }
    }

    public String writeChanges(List<AuditChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (Exception e) {
            return null;
        }
    }

    private static AuditTargetType parseType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return AuditTargetType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<AuditTargetType, Set<String>> existingTargets(List<AuditLog> rows) {
        Map<AuditTargetType, Set<UUID>> ids = new EnumMap<>(AuditTargetType.class);
        for (AuditLog row : rows) {
            AuditTargetType type = parseType(row.getTargetType());
            if (type == null || row.getTargetId() == null) {
                continue;
            }
            try {
                ids.computeIfAbsent(type, t -> new HashSet<>()).add(UUID.fromString(row.getTargetId()));
            } catch (IllegalArgumentException ignored) {
                // identifiant non UUID (compte utilisateur, reglage) : pas de verification en base
            }
        }
        Map<AuditTargetType, Set<String>> existing = new EnumMap<>(AuditTargetType.class);
        existing.put(AuditTargetType.APPLICATION, found(applicationRepository, ids.get(AuditTargetType.APPLICATION), a -> a.getId()));
        existing.put(AuditTargetType.SCENARIO, found(scenarioRepository, ids.get(AuditTargetType.SCENARIO), s -> s.getId()));
        existing.put(AuditTargetType.EXECUTION, found(executionRepository, ids.get(AuditTargetType.EXECUTION), e -> e.getId()));
        existing.put(AuditTargetType.SCHEDULE, found(scheduledExecutionRepository, ids.get(AuditTargetType.SCHEDULE), s -> s.getId()));
        return existing;
    }

    private static <T> Set<String> found(JpaRepository<T, UUID> repository, Collection<UUID> ids, Function<T, UUID> idOf) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        repository.findAllById(ids).forEach(entity -> result.add(idOf.apply(entity).toString()));
        return result;
    }
}
