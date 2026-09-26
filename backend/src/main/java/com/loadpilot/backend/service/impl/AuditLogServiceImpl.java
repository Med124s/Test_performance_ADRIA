package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.dto.response.AuditStatsResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.AuditLogMapper;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.security.RequestIpResolver;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.specification.AuditLogSpecifications;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * "record" est volontairement NON @Transactional sur cette classe : la
 * veritable ecriture transactionnelle (REQUIRES_NEW) vit sur le bean separe
 * AuditLogWriter, et record() englobe TOUT l'appel (invocation + commit
 * eventuel) dans un try/catch - garantissant qu'AUCUNE exception liee a
 * l'audit (y compris une exception levee au moment du commit de la
 * transaction REQUIRES_NEW, qui se produirait APRES le retour de la methode
 * si le @Transactional etait pose ici) ne peut jamais remonter jusqu'a
 * l'appelant metier (voir Phase 12, section 15).
 */
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogServiceImpl.class);
    private static final int DESCRIPTION_MAX_LENGTH = 500;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "date");
    private static final String CSV_HEADER = "id,userId,username,action,module,date,ip,result,description\r\n";

    private final AuditLogRepository auditLogRepository;
    private final AuditLogMapper auditLogMapper;
    private final AuditLogWriter auditLogWriter;

    @Override
    public void record(AuditAction action, AuditModule module, AuditResult result, String description) {
        try {
            CurrentUser currentUser = resolveCurrentUser();
            AuditLog entry = AuditLog.builder()
                    .userId(currentUser != null ? currentUser.subject() : null)
                    .username(currentUser != null ? currentUser.username() : null)
                    .action(action)
                    .module(module)
                    .date(Instant.now())
                    .ip(resolveIp())
                    .result(result)
                    .description(truncate(description))
                    .build();
            auditLogWriter.write(entry);
        } catch (Exception e) {
            // Jamais de donnee sensible loggee ici - uniquement la classe
            // d'exception (voir Phase 12, section 20).
            log.warn("Ecriture de l'AuditLog echouee ({} / {} / {}) : {}", action, module, result, e.getClass().getSimpleName());
        }
    }

    @Override
    public void record(com.loadpilot.backend.entity.AppUser actor, AuditAction action, AuditModule module,
                        AuditResult result, String description) {
        try {
            AuditLog entry = AuditLog.builder()
                    .userId(actor != null ? actor.getKeycloakSubject() : null)
                    .username(actor != null ? actor.getUsername() : null)
                    .action(action)
                    .module(module)
                    .date(Instant.now())
                    .ip(null)
                    .result(result)
                    .description(truncate(description))
                    .build();
            auditLogWriter.write(entry);
        } catch (Exception e) {
            log.warn("Ecriture de l'AuditLog echouee ({} / {} / {}) : {}", action, module, result, e.getClass().getSimpleName());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AuditLogResponse getById(UUID id) {
        AuditLog auditLog = auditLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditLog introuvable : " + id));
        return auditLogMapper.toResponse(auditLog);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AuditLogResponse> search(AuditLogFilterRequest filter, int page, int size) {
        Specification<AuditLog> specification = AuditLogSpecifications.withFilters(filter);
        Page<AuditLog> result = auditLogRepository.findAll(specification, PageRequest.of(page, size, NEWEST_FIRST));
        return new PagedResponse<>(
                auditLogMapper.toResponseList(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public AuditStatsResponse getStats() {
        long total = auditLogRepository.count();
        long successful = auditLogRepository.countByResult(AuditResult.SUCCESS);
        long failed = auditLogRepository.countByResult(AuditResult.FAILURE);

        Map<String, Long> byModule = new HashMap<>();
        for (Object[] row : auditLogRepository.countGroupedByModule()) {
            byModule.put(String.valueOf(row[0]), (Long) row[1]);
        }
        Map<String, Long> byAction = new HashMap<>();
        for (Object[] row : auditLogRepository.countGroupedByAction()) {
            byAction.put(String.valueOf(row[0]), (Long) row[1]);
        }

        return new AuditStatsResponse(total, successful, failed, byModule, byAction);
    }

    @Override
    @Transactional(readOnly = true)
    public String exportCsv(AuditLogFilterRequest filter) {
        Specification<AuditLog> specification = AuditLogSpecifications.withFilters(filter);
        List<AuditLog> rows = auditLogRepository.findAll(specification, NEWEST_FIRST);
        return toCsv(auditLogMapper.toResponseList(rows));
    }

    private String toCsv(List<AuditLogResponse> rows) {
        StringBuilder csv = new StringBuilder(CSV_HEADER);
        for (AuditLogResponse row : rows) {
            csv.append(csvField(row.id())).append(',')
                    .append(csvField(row.userId())).append(',')
                    .append(csvField(row.username())).append(',')
                    .append(csvField(row.action() != null ? row.action().name() : null)).append(',')
                    .append(csvField(row.module() != null ? row.module().name() : null)).append(',')
                    .append(csvField(row.date() != null ? row.date().toString() : null)).append(',')
                    .append(csvField(row.ip())).append(',')
                    .append(csvField(row.result() != null ? row.result().name() : null)).append(',')
                    .append(csvField(row.description()))
                    .append("\r\n");
        }
        return csv.toString();
    }

    /** Echappement CSV (RFC 4180) : un champ contenant une virgule, un
     * guillemet ou un retour a la ligne est entoure de guillemets, et tout
     * guillemet interne est double. */
    private String csvField(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }

    private String truncate(String description) {
        if (description == null) {
            return null;
        }
        return description.length() > DESCRIPTION_MAX_LENGTH ? description.substring(0, DESCRIPTION_MAX_LENGTH) : description;
    }

    private CurrentUser resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return CurrentUser.from(jwtAuthenticationToken.getToken(), jwtAuthenticationToken);
        }
        return null;
    }

    private String resolveIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return RequestIpResolver.resolve(servletRequestAttributes.getRequest());
        }
        return null;
    }
}
