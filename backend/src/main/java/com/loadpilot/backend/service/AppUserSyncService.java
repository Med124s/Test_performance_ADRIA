package com.loadpilot.backend.service;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.security.CurrentUser;

/**
 * Cree ou met a jour (par sujet Keycloak) la projection locale AppUser d'un
 * utilisateur authentifie - partage par ProfileService (Phase 5) et
 * ApplicationService (Phase 6, pour renseigner "createdBy"), pour ne pas
 * dupliquer cette logique.
 */
public interface AppUserSyncService {

    AppUser sync(CurrentUser currentUser);
}
