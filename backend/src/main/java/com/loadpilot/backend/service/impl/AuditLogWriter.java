package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecriture reelle d'un AuditLog dans SA PROPRE transaction (REQUIRES_NEW),
 * toujours suspendue independamment de la transaction metier appelante -
 * bean separe (meme raison que ExecutionTransactionHelper/
 * MetricGenerationService : un appel this.methode() depuis la meme classe
 * ne passe pas par le proxy Spring, @Transactional serait alors ignore).
 *
 * Cette classe elle-meme NE CAPTURE AUCUNE exception : c'est
 * AuditLogServiceImpl.record(...), qui invoque cette methode depuis un
 * contexte NON transactionnel, qui absorbe integralement tout echec
 * (y compris un echec au moment du commit de cette transaction REQUIRES_NEW)
 * - voir le commentaire de record() pour le detail de cette garantie.
 */
@Service
@RequiredArgsConstructor
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLog entry) {
        auditLogRepository.saveAndFlush(entry);
    }
}
