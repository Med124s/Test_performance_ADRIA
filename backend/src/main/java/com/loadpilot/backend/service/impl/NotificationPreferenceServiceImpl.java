package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.response.NotificationPreferenceResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.NotificationPreference;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.NotificationPreferenceRepository;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import com.loadpilot.backend.service.NotificationPreferenceService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Preferences de notification ; une ligne n'existe que pour un type dont l'utilisateur a change l'etat par defaut (actif). */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final AppUserRepository appUserRepository;
    private final AppUserSyncService appUserSyncService;

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> list(String keycloakSubject) {
        Map<NotificationType, NotificationPreference> existing = appUserRepository.findByKeycloakSubject(keycloakSubject)
                .map(u -> notificationPreferenceRepository.findByAppUserId(u.getId()).stream()
                        .collect(java.util.stream.Collectors.toMap(NotificationPreference::getNotificationType, p -> p)))
                .orElse(Map.of());

        return java.util.Arrays.stream(NotificationType.values())
                .map(type -> {
                    NotificationPreference p = existing.get(type);
                    return new NotificationPreferenceResponse(type, p == null || p.isEnabled(), p != null && p.isEmailEnabled());
                })
                .toList();
    }

    @Override
    @Transactional
    public NotificationPreferenceResponse setEnabled(CurrentUser currentUser, NotificationType type, boolean enabled) {
        return update(currentUser, type, enabled, null);
    }

    @Override
    @Transactional
    public NotificationPreferenceResponse update(CurrentUser currentUser, NotificationType type, Boolean enabled, Boolean emailEnabled) {
        // Cree la copie locale de l'utilisateur si besoin.
        AppUser appUser = appUserSyncService.sync(currentUser);

        NotificationPreference preference = notificationPreferenceRepository
                .findByAppUserIdAndNotificationType(appUser.getId(), type)
                .orElseGet(() -> NotificationPreference.builder()
                        .appUser(appUser)
                        .notificationType(type)
                        .enabled(true)
                        .build());
        if (enabled != null) preference.setEnabled(enabled);
        if (emailEnabled != null) preference.setEmailEnabled(emailEnabled);
        preference = notificationPreferenceRepository.save(preference);
        return new NotificationPreferenceResponse(preference.getNotificationType(), preference.isEnabled(), preference.isEmailEnabled());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID appUserId, NotificationType type) {
        return notificationPreferenceRepository.findByAppUserIdAndNotificationType(appUserId, type)
                .map(NotificationPreference::isEnabled)
                .orElse(true);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmailEnabled(UUID appUserId, NotificationType type) {
        return notificationPreferenceRepository.findByAppUserIdAndNotificationType(appUserId, type)
                .map(NotificationPreference::isEmailEnabled)
                .orElse(false);
    }
}
