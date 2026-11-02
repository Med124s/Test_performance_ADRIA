package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.NotificationResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Notification;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.NotificationMapper;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.NotificationRepository;
import com.loadpilot.backend.service.NotificationPreferenceService;
import com.loadpilot.backend.service.NotificationService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Notifications : creation, liste, lecture et suppression (seulement celles de l'utilisateur connecte). */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);
    private static final int MESSAGE_MAX_LENGTH = 1000;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");
    /** Taille maximale d'une page de la liste. */
    static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notificationRepository;
    private final AppUserRepository appUserRepository;
    private final NotificationMapper notificationMapper;
    private final NotificationPreferenceService notificationPreferenceService;
    private final com.loadpilot.backend.service.mail.MailService mailService;

    @Override
    @Transactional
    public void create(UUID recipientAppUserId, NotificationType type, String title, String message,
                        UUID relatedExecutionId, UUID relatedScheduleId) {
        if (recipientAppUserId == null) {
            // Destinataire inconnu : aucune notification.
            return;
        }
        try {
            Optional<AppUser> recipient = appUserRepository.findById(recipientAppUserId);
            if (recipient.isEmpty()) {
                // Destinataire introuvable : aucune notification.
                return;
            }
            // Preferences de l'utilisateur pour ce type.
            boolean inApp = notificationPreferenceService.isEnabled(recipientAppUserId, type);
            boolean email = mailService.available() && notificationPreferenceService.isEmailEnabled(recipientAppUserId, type);
            if (email) {
                sendEmail(recipient.get(), title, message, relatedExecutionId);
            }
            if (!inApp) {
                return;
            }
            Notification notification = Notification.builder()
                    .recipient(recipient.get())
                    .type(type)
                    .title(title)
                    .message(message != null && message.length() > MESSAGE_MAX_LENGTH
                            ? message.substring(0, MESSAGE_MAX_LENGTH) : message)
                    .relatedExecutionId(relatedExecutionId)
                    .relatedScheduleId(relatedScheduleId)
                    .read(false)
                    .createdAt(Instant.now())
                    .build();
            notificationRepository.save(notification);
        } catch (Exception e) {
            log.warn("Creation de notification echouee (type={}, recipientAppUserId={}) : {}",
                    type, recipientAppUserId, e.getClass().getSimpleName());
        }
    }

    /** Copie de la notification par e-mail, en arriere-plan. */
    private void sendEmail(AppUser recipient, String title, String message, UUID relatedExecutionId) {
        String to = recipient.getEmail();
        if (to == null || to.isBlank()) return;
        StringBuilder body = new StringBuilder();
        body.append(title == null ? "" : title).append("\n\n");
        if (message != null && !message.isBlank()) body.append(message).append("\n\n");
        if (relatedExecutionId != null) {
            body.append("Voir le rapport : ").append(mailService.appUrl()).append("/executions/report/").append(relatedExecutionId).append("\n\n");
        }
        body.append("—\nLoadPilot. Pour ne plus recevoir ces e-mails : Paramètres > Mon profil > Notifications.");
        mailService.sendAsync(to, "[LoadPilot] " + (title == null ? "Notification" : title), body.toString());
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<NotificationResponse> list(String keycloakSubject, Boolean readFilter, int page, int size) {
        return list(keycloakSubject, readFilter, null, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<NotificationResponse> list(String keycloakSubject, Boolean readFilter,
                                                    java.util.Collection<NotificationType> types, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Optional<AppUser> recipient = appUserRepository.findByKeycloakSubject(keycloakSubject);
        if (recipient.isEmpty()) {
            return new PagedResponse<>(java.util.List.of(), safePage, safeSize, 0, 0);
        }
        boolean allTypes = types == null || types.isEmpty();
        // Tri stable : date puis id.
        PageRequest pageRequest = PageRequest.of(safePage, safeSize, NEWEST_FIRST.and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Notification> result = notificationRepository.search(recipient.get().getId(), readFilter, allTypes,
                allTypes ? java.util.List.of(NotificationType.EXECUTION_SUCCESS) : types, pageRequest);
        return new PagedResponse<>(
                notificationMapper.toResponseList(result.getContent()),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(String keycloakSubject) {
        return appUserRepository.findByKeycloakSubject(keycloakSubject)
                .map(u -> notificationRepository.countByRecipientIdAndReadFalse(u.getId()))
                .orElse(0L);
    }

    @Override
    @Transactional
    public NotificationResponse markRead(String keycloakSubject, UUID id) {
        AppUser recipient = appUserRepository.findByKeycloakSubject(keycloakSubject)
                .orElseThrow(() -> new ResourceNotFoundException("Notification introuvable : " + id));
        Notification notification = notificationRepository.findByIdAndRecipientId(id, recipient.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification introuvable : " + id));
        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(Instant.now());
            notification = notificationRepository.save(notification);
        }
        return notificationMapper.toResponse(notification);
    }

    @Override
    @Transactional
    public int markAllRead(String keycloakSubject) {
        return appUserRepository.findByKeycloakSubject(keycloakSubject)
                .map(u -> notificationRepository.markAllReadForRecipient(u.getId(), Instant.now()))
                .orElse(0);
    }

    @Override
    @Transactional
    public void delete(String keycloakSubject, UUID id) {
        AppUser recipient = appUserRepository.findByKeycloakSubject(keycloakSubject)
                .orElseThrow(() -> new ResourceNotFoundException("Notification introuvable : " + id));
        Notification notification = notificationRepository.findByIdAndRecipientId(id, recipient.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification introuvable : " + id));
        notificationRepository.delete(notification);
    }
}
