package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.NotificationPreferenceUpdateRequest;
import com.loadpilot.backend.dto.response.NotificationPreferenceResponse;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.NotificationPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/notification-preferences — PERSONNEL a l'utilisateur authentifie
 * courant (meme convention que /api/notifications, voir
 * NotificationController) : aucun parametre "userId" nulle part, toujours
 * resolu depuis le Jwt. Ouvert a tout role authentifie - gerer SES PROPRES
 * preferences n'est jamais une action privilegiee.
 *
 * P1-D — remplace la page NotificationsPreferences.tsx precedemment 100%
 * fictive (canaux Slack/Teams/SMS/Email non implementes, regles d'alerte
 * sur seuils CPU/RAM inexistants) : ici, seul le canal in-app REEL (voir
 * P1-B) peut etre coupe par type d'evenement.
 */
@RestController
@RequestMapping("/api/notification-preferences")
@RequiredArgsConstructor
@Tag(name = "Notification Preferences", description = "Preferences reelles (in-app uniquement) de l'utilisateur authentifie par type de notification")
public class NotificationPreferenceController {

    private final NotificationPreferenceService notificationPreferenceService;

    @GetMapping
    @Operation(summary = "Lister mes preferences de notification", description = "Les 5 types reels de Notification (voir NotificationType), chacun avec son etat reel (actif par defaut).")
    public List<NotificationPreferenceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return notificationPreferenceService.list(jwt.getSubject());
    }

    @PatchMapping("/{type}")
    @Operation(summary = "Activer/desactiver un type de notification", description = "N'affecte que les notifications in-app futures - n'efface jamais l'historique deja recu.")
    public NotificationPreferenceResponse setEnabled(@AuthenticationPrincipal Jwt jwt,
                                                       Authentication authentication,
                                                       @PathVariable NotificationType type,
                                                       @Valid @RequestBody NotificationPreferenceUpdateRequest request) {
        CurrentUser currentUser = CurrentUser.from(jwt, authentication);
        return notificationPreferenceService.setEnabled(currentUser, type, request.enabled());
    }
}
