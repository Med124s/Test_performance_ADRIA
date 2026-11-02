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

/** Preferences de notification de l'utilisateur connecte. */
@RestController
@RequestMapping("/api/notification-preferences")
@RequiredArgsConstructor
@Tag(name = "Notification Preferences", description = "Preferences de notification de l'utilisateur connecte, par type (dans l'application et par e-mail)")
public class NotificationPreferenceController {

    private final NotificationPreferenceService notificationPreferenceService;
    private final com.loadpilot.backend.service.mail.MailService mailService;

    @GetMapping
    @Operation(summary = "Lister mes preferences de notification", description = "Chaque type de notification (voir NotificationType) avec son etat pour l'utilisateur ; actif par defaut.")
    public List<NotificationPreferenceResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return notificationPreferenceService.list(jwt.getSubject());
    }

    @GetMapping("/channels")
    @Operation(summary = "Canaux disponibles", description = "email : un serveur e-mail est configure et le compte a une adresse.")
    public java.util.Map<String, Object> channels(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        boolean hasEmail = email != null && !email.isBlank();
        return java.util.Map.of("inApp", true, "email", mailService.available() && hasEmail, "emailServer", mailService.available(),
                "emailAddress", hasEmail ? email : "");
    }

    @PatchMapping("/{type}")
    @Operation(summary = "Activer/desactiver un type de notification",
            description = "enabled : dans l'application ; emailEnabled : par e-mail (null = inchange). N'affecte que les notifications futures.")
    public NotificationPreferenceResponse setEnabled(@AuthenticationPrincipal Jwt jwt,
                                                       Authentication authentication,
                                                       @PathVariable NotificationType type,
                                                       @Valid @RequestBody NotificationPreferenceUpdateRequest request) {
        CurrentUser currentUser = CurrentUser.from(jwt, authentication);
        return notificationPreferenceService.update(currentUser, type, request.enabled(), request.emailEnabled());
    }
}
