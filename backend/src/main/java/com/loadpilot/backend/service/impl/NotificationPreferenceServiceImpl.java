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

/**
 * P1-D — modele "opt-out" (voir NotificationPreference) : une ligne
 * n'existe que si l'utilisateur a explicitement change l'etat par defaut
 * (true) pour au moins un type - jamais 5 lignes creees d'office au premier
 * appel.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final AppUserRepository appUserRepository;
    private final AppUserSyncService appUserSyncService;

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> list(String keycloakSubject) {
        Map<NotificationType, Boolean> existing = appUserRepository.findByKeycloakSubject(keycloakSubject)
                .map(u -> notificationPreferenceRepository.findByAppUserId(u.getId()).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                NotificationPreference::getNotificationType, NotificationPreference::isEnabled)))
                .orElse(Map.of());

        return java.util.Arrays.stream(NotificationType.values())
                .map(type -> new NotificationPreferenceResponse(type, existing.getOrDefault(type, true)))
                .toList();
    }

    @Override
    @Transactional
    public NotificationPreferenceResponse setEnabled(CurrentUser currentUser, NotificationType type, boolean enabled) {
        // sync() cree reellement l'AppUser au besoin (premiere action d'un
        // utilisateur qui n'a encore jamais declenche AppUserSyncService -
        // ex: la toute premiere chose qu'il fait apres connexion est de
        // couper une preference de notification) - jamais une simple
        // lecture qui echouerait avec un 404 trompeur ("utilisateur
        // introuvable" alors qu'il est bien authentifie, juste jamais
        // encore synchronise).
        AppUser appUser = appUserSyncService.sync(currentUser);

        NotificationPreference preference = notificationPreferenceRepository
                .findByAppUserIdAndNotificationType(appUser.getId(), type)
                .orElseGet(() -> NotificationPreference.builder()
                        .appUser(appUser)
                        .notificationType(type)
                        .build());
        preference.setEnabled(enabled);
        preference = notificationPreferenceRepository.save(preference);
        return new NotificationPreferenceResponse(preference.getNotificationType(), preference.isEnabled());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID appUserId, NotificationType type) {
        return notificationPreferenceRepository.findByAppUserIdAndNotificationType(appUserId, type)
                .map(NotificationPreference::isEnabled)
                .orElse(true);
    }
}
