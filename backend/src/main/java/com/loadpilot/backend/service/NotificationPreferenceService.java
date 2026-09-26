package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.NotificationPreferenceResponse;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.security.CurrentUser;
import java.util.List;
import java.util.UUID;

public interface NotificationPreferenceService {

    /** Les 5 NotificationType reels (voir enums.NotificationType), chacun
     * avec son etat reel pour cet utilisateur (true par defaut en l'absence
     * de ligne explicite - modele "opt-out"). */
    List<NotificationPreferenceResponse> list(String keycloakSubject);

    /**
     * "currentUser" (pas un simple sujet Keycloak) : contrairement a list()
     * (lecture pure, degrade silencieusement si l'AppUser n'existe pas
     * encore), setEnabled() DOIT garantir que l'AppUser existe avant
     * d'ecrire une ligne notification_preference (contrainte de FK) - voir
     * AppUserSyncService.sync, qui cree la projection locale au premier
     * appel si necessaire (meme garantie que ProfileServiceImpl/
     * ScenarioServiceImpl pour toute premiere action d'un utilisateur).
     */
    NotificationPreferenceResponse setEnabled(CurrentUser currentUser, NotificationType type, boolean enabled);

    /** Utilise UNIQUEMENT par NotificationServiceImpl.create() avant de
     * generer une Notification - jamais expose directement via l'API.
     * true si aucune preference explicite n'existe (modele "opt-out"). */
    boolean isEnabled(UUID appUserId, NotificationType type);
}
