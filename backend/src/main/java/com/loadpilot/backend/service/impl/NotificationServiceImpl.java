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

/**
 * P1-B — voir NotificationService pour les garanties (jamais de mock, un
 * utilisateur ne voit/ne modifie jamais que SES PROPRES notifications).
 */
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);
    private static final int MESSAGE_MAX_LENGTH = 1000;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final NotificationRepository notificationRepository;
    private final AppUserRepository appUserRepository;
    private final NotificationMapper notificationMapper;
    private final NotificationPreferenceService notificationPreferenceService;

    @Override
    @Transactional
    public void create(UUID recipientAppUserId, NotificationType type, String title, String message,
                        UUID relatedExecutionId, UUID relatedScheduleId) {
        if (recipientAppUserId == null) {
            // Jamais fabrique de destinataire (ex: Execution historique sans
            // triggeredBy connu, voir Execution.triggeredBy) - simplement
            // aucune notification n'est generee, jamais une exception qui
            // ferait echouer l'evenement metier reel appelant.
            return;
        }
        try {
            Optional<AppUser> recipient = appUserRepository.findById(recipientAppUserId);
            if (recipient.isEmpty()) {
                // L'AppUser a disparu entre le declenchement de l'evenement
                // et cette ecriture (cas theorique, aucune suppression
                // d'AppUser n'existe a ce jour) - jamais une notification
                // orpheline creee malgre tout.
                return;
            }
            // P1-D — respecte reellement la preference de l'utilisateur
            // (voir NotificationPreferenceService, modele "opt-out") :
            // jamais un simple affichage cote frontend qui prétendrait
            // couper des notifications toujours generees cote backend.
            if (!notificationPreferenceService.isEnabled(recipientAppUserId, type)) {
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

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<NotificationResponse> list(String keycloakSubject, Boolean readFilter, int page, int size) {
        Optional<AppUser> recipient = appUserRepository.findByKeycloakSubject(keycloakSubject);
        if (recipient.isEmpty()) {
            return new PagedResponse<>(java.util.List.of(), page, size, 0, 0);
        }
        PageRequest pageRequest = PageRequest.of(page, size, NEWEST_FIRST);
        Page<Notification> result = readFilter != null
                ? notificationRepository.findByRecipientIdAndReadOrderByCreatedAtDesc(recipient.get().getId(), readFilter, pageRequest)
                : notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipient.get().getId(), pageRequest);
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
