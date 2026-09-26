package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.NotificationType;

/** P1-D — un type de Notification et son etat REEL pour l'utilisateur
 * courant (voir NotificationPreferenceServiceImpl - modele "opt-out",
 * "enabled=true" par defaut en l'absence de toute preference explicite). */
public record NotificationPreferenceResponse(
        NotificationType type,
        boolean enabled
) {
}
