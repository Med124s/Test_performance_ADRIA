package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.NotificationType;

/** Preference de notification de l'utilisateur pour un type. */
public record NotificationPreferenceResponse(
        NotificationType type,
        /** Dans l'application (actif par defaut). */
        boolean enabled,
        /** Par e-mail (desactive par defaut). */
        boolean emailEnabled
) {
}
