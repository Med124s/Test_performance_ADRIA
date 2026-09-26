package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.NotificationResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.enums.NotificationType;
import java.util.UUID;

public interface NotificationService {

    /**
     * Cree reellement une Notification pour l'AppUser d'id
     * "recipientAppUserId" - ne leve JAMAIS d'exception (meme garantie
     * qu'AuditLogService.record) : un echec de creation de notification ne
     * doit jamais faire echouer l'evenement metier reel qui l'a
     * declenchee (fin d'Execution, declenchement de ScheduledExecution...).
     * "recipientAppUserId" null (ex: Execution sans triggeredBy connu) =
     * n'emet simplement aucune notification, jamais une exception. Un UUID
     * brut (jamais une entite AppUser) : voir ExecutionService.execute
     * pour la justification complete de ce choix a travers tout P1-B.
     */
    void create(UUID recipientAppUserId, NotificationType type, String title, String message,
                UUID relatedExecutionId, UUID relatedScheduleId);

    /** Notifications du SEUL utilisateur authentifie courant - jamais celles d'un autre. */
    PagedResponse<NotificationResponse> list(String keycloakSubject, Boolean readFilter, int page, int size);

    long countUnread(String keycloakSubject);

    /** @throws com.loadpilot.backend.exception.ResourceNotFoundException si absente ou n'appartenant pas a cet utilisateur. */
    NotificationResponse markRead(String keycloakSubject, UUID id);

    /** @return le nombre de notifications reellement marquees lues. */
    int markAllRead(String keycloakSubject);

    /** @throws com.loadpilot.backend.exception.ResourceNotFoundException si absente ou n'appartenant pas a cet utilisateur. */
    void delete(String keycloakSubject, UUID id);
}
