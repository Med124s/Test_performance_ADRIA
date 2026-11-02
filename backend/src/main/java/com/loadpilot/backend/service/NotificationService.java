package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.NotificationResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.enums.NotificationType;
import java.util.UUID;

public interface NotificationService {

    /** Cree une notification pour un utilisateur ; ne leve jamais d'exception. */
    void create(UUID recipientAppUserId, NotificationType type, String title, String message,
                UUID relatedExecutionId, UUID relatedScheduleId);

    /** Notifications de l'utilisateur connecte. */
    PagedResponse<NotificationResponse> list(String keycloakSubject, Boolean readFilter, int page, int size);

    /** Notifications de l'utilisateur, filtrees par types (null ou vide : tous), les plus recentes en premier. */
    PagedResponse<NotificationResponse> list(String keycloakSubject, Boolean readFilter,
                                             java.util.Collection<NotificationType> types, int page, int size);

    long countUnread(String keycloakSubject);

    /** @throws com.loadpilot.backend.exception.ResourceNotFoundException si absente ou n'appartenant pas a cet utilisateur. */
    NotificationResponse markRead(String keycloakSubject, UUID id);

    /** @return le nombre de notifications reellement marquees lues. */
    int markAllRead(String keycloakSubject);

    /** @throws com.loadpilot.backend.exception.ResourceNotFoundException si absente ou n'appartenant pas a cet utilisateur. */
    void delete(String keycloakSubject, UUID id);
}
