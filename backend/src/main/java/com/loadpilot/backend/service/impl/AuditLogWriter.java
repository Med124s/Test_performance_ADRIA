package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ecriture d'une entree d'audit dans sa propre transaction (REQUIRES_NEW). */
@Service
@RequiredArgsConstructor
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLog entry) {
        auditLogRepository.saveAndFlush(entry);
    }
}
