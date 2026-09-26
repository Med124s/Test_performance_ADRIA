package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.response.NotificationResponse;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/notifications — PERSONNEL a l'utilisateur authentifie courant :
 * aucun parametre "userId" n'existe sur aucun de ces endpoints (jamais
 * fourni par le client) - le destinataire est TOUJOURS resolu depuis le
 * Jwt de la requete (claim "sub", voir NotificationServiceImpl). Ouvert a
 * tout role authentifie (voir SecurityConfig) : consulter/marquer/
 * supprimer SES PROPRES notifications n'est pas une action privilegiee.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Notifications reelles de l'utilisateur authentifie (executions terminees, planifications declenchees)")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "Lister mes notifications", description = "Les plus recentes en premier. ?read=true/false filtre optionnellement par statut de lecture.")
    public PagedResponse<NotificationResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Boolean read,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return notificationService.list(jwt.getSubject(), read, page, size);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Nombre de notifications non lues", description = "Pense pour le badge de la barre laterale (polling leger).")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", notificationService.countUnread(jwt.getSubject()));
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Marquer une notification comme lue")
    public NotificationResponse markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return notificationService.markRead(jwt.getSubject(), id);
    }

    @PatchMapping("/read-all")
    @Operation(summary = "Marquer toutes mes notifications comme lues")
    public Map<String, Integer> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("updated", notificationService.markAllRead(jwt.getSubject()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une notification")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        notificationService.delete(jwt.getSubject(), id);
    }
}
