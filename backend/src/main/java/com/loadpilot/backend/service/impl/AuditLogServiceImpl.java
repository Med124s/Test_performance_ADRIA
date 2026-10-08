package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.dto.response.AuditStatsResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.repository.AuditLogRepository;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.security.RequestIpResolver;
import com.loadpilot.backend.service.AppSettingsService;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.audit.AuditChange;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditLogPresenter;
import com.loadpilot.backend.service.audit.AuditSanitizer;
import com.loadpilot.backend.specification.AuditLogSpecifications;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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
 * l'audit ne peut jamais remonter jusqu'a l'appelant metier (voir Phase 12,
 * section 15).
 */
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogServiceImpl.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "date");
    private static final int MAX_EXPORT_ROWS = 50_000;
    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AuditLogRepository auditLogRepository;
    private final AuditLogWriter auditLogWriter;
    private final AuditLogPresenter presenter;
    private final AppSettingsService settings;
    private final RequestIpResolver ipResolver;

    /** Derniere seconde ou chaque action (cle complete) a ete ecrite : anti-doublon. */
    private final Map<String, Long> lastWritten = new ConcurrentHashMap<>();

    @Override
    public void record(AuditEvent event) {
        try {
            if (event.isConsultation() && !settings.logReads()) {
                return;
            }
            HttpServletRequest request = currentRequest();
            boolean fromRequest = !event.isImported() && !event.isSystem();
            CurrentUser user = fromRequest ? resolveCurrentUser() : null;
            Instant date = event.date() != null ? event.date() : Instant.now();

            AuditLog entry = AuditLog.builder()
                    .actorType(event.isSystem() ? "SYSTEM" : "USER")
                    .actorSource(clip(AuditSanitizer.clean(event.source()), 255))
                    .userId(event.isImported() ? event.actorUserId() : user != null ? user.subject() : null)
                    .username(clip(event.isImported() ? event.actorUsername() : user != null ? user.username() : null, 255))
                    .action(event.action())
                    .module(event.module())
                    .date(date)
                    .ip(event.isImported() ? clip(event.ip(), 45) : request != null ? ipResolver.resolve(request) : null)
                    .userAgent(fromRequest && request != null ? clip(request.getHeader("User-Agent"), 300) : null)
                    .result(event.result())
                    .description(clip(AuditSanitizer.clean(event.description()), 500))
                    .targetType(event.targetType() != null ? event.targetType().name() : null)
                    .targetId(clip(event.targetId(), 64))
                    .targetLabel(clip(AuditSanitizer.clean(event.targetLabel()), 255))
                    .changes(changesJson(event.changes()))
                    .externalId(event.externalId())
                    .build();

            if (!event.isImported() && isDuplicate(entry)) {
                return;
            }
            auditLogWriter.write(entry);
        } catch (Exception e) {
            // Jamais de donnee sensible loggee ici - uniquement la classe d'exception (voir Phase 12, section 20).
            log.warn("Ecriture de l'AuditLog echouee ({} / {} / {}) : {}", event.action(), event.module(), event.result(), e.getClass().getSimpleName());
        }
    }

    /** Meme acteur, meme action, meme objet, meme resultat et meme texte dans la meme seconde : ignore. */
    private boolean isDuplicate(AuditLog entry) {
        String key = String.join("|", entry.getActorType(), String.valueOf(entry.getUserId()), String.valueOf(entry.getUsername()),
                String.valueOf(entry.getActorSource()), entry.getAction().name(), entry.getModule().name(),
                String.valueOf(entry.getTargetId()), entry.getResult().name(), String.valueOf(entry.getDescription()),
                String.valueOf(entry.getChanges()));
        long second = entry.getDate().getEpochSecond();
        Long previous = lastWritten.put(key, second);
        if (lastWritten.size() > 2_000) {
            long now = Instant.now().getEpochSecond();
            lastWritten.values().removeIf(s -> s < now - 5);
        }
        return previous != null && previous == second;
    }

    private String changesJson(List<AuditChange> changes) {
        if (changes.isEmpty()) {
            return null;
        }
        List<AuditChange> cleaned = changes.stream()
                .map(c -> new AuditChange(clip(c.label(), 100), clip(AuditSanitizer.clean(c.before()), 300), clip(AuditSanitizer.clean(c.after()), 300)))
                .collect(Collectors.toCollection(ArrayList::new));
        String json = presenter.writeChanges(cleaned);
        while (json != null && json.length() > 4000 && cleaned.size() > 1) {
            cleaned.remove(cleaned.size() - 1);
            json = presenter.writeChanges(cleaned);
        }
        return json != null && json.length() <= 4000 ? json : null;
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }

    @Override
    @Transactional(readOnly = true)
    public AuditLogResponse getById(UUID id) {
        AuditLog auditLog = auditLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Entrée du journal introuvable : " + id));
        return presenter.present(auditLog);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AuditLogResponse> search(AuditLogFilterRequest filter, int page, int size) {
        Specification<AuditLog> specification = AuditLogSpecifications.withFilters(filter);
        Page<AuditLog> result = auditLogRepository.findAll(specification,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), NEWEST_FIRST));
        return new PagedResponse<>(
                presenter.present(result.getContent()),
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
    public List<String> actors() {
        return auditLogRepository.findDistinctActorUsernames();
    }

    @Override
    @Transactional(readOnly = true)
    public String exportCsv(AuditLogFilterRequest filter) {
        Specification<AuditLog> specification = AuditLogSpecifications.withFilters(filter);
        List<AuditLog> rows = auditLogRepository.findAll(specification, PageRequest.of(0, MAX_EXPORT_ROWS, NEWEST_FIRST)).getContent();
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("Date;Utilisateur;Catégorie;Action;Détail;Avant → après;Résultat;Adresse IP\r\n");
        for (AuditLogResponse row : presenter.present(rows)) {
            String actor = "SYSTEM".equals(row.actorType())
                    ? "Système" + (row.actorSource() != null ? " (" + row.actorSource() + ")" : "")
                    : row.username() != null ? row.username() : "—";
            String changes = row.changes().stream()
                    .map(c -> c.label() + " : " + (c.before() != null ? c.before() : "—") + " → " + (c.after() != null ? c.after() : "—"))
                    .collect(Collectors.joining(" ; "));
            csv.append(csvField(row.date() != null ? CSV_DATE.format(row.date()) : "")).append(';')
                    .append(csvField(actor)).append(';')
                    .append(csvField(row.categoryLabel())).append(';')
                    .append(csvField(row.summary())).append(';')
                    .append(csvField(row.detail())).append(';')
                    .append(csvField(changes)).append(';')
                    .append(csvField(row.resultLabel())).append(';')
                    .append(csvField(row.ipLabel()))
                    .append("\r\n");
        }
        return csv.toString();
    }

    /** Echappement CSV : champ entre guillemets s'il contient « ; », un guillemet ou un retour a la ligne. */
    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        String safe = AuditSanitizer.clean(value);
        // Pas d'execution de formule a l'ouverture dans un tableur (injection CSV).
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        boolean needsQuoting = safe.contains(";") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r");
        String escaped = safe.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }

    private CurrentUser resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return CurrentUser.from(jwtAuthenticationToken.getToken(), jwtAuthenticationToken);
        }
        return null;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletRequestAttributes ? servletRequestAttributes.getRequest() : null;
    }
}
