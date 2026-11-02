package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.NotificationPreferenceResponse;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.security.CurrentUser;
import java.util.List;
import java.util.UUID;

public interface NotificationPreferenceService {

    /** Preferences de chaque type de notification pour cet utilisateur (actif par defaut). */
    List<NotificationPreferenceResponse> list(String keycloakSubject);

    /** Active ou desactive un type de notification pour l'utilisateur connecte. */
    NotificationPreferenceResponse setEnabled(CurrentUser currentUser, NotificationType type, boolean enabled);

    /** Canaux d'un type : dans l'application et par e-mail ; null = inchange. */
    NotificationPreferenceResponse update(CurrentUser currentUser, NotificationType type, Boolean enabled, Boolean emailEnabled);

    /** true si le type est actif pour cet utilisateur (actif par defaut). */
    boolean isEnabled(UUID appUserId, NotificationType type);

    /** Aussi par e-mail (desactive par defaut, en l'absence de ligne). */
    boolean isEmailEnabled(UUID appUserId, NotificationType type);
}
