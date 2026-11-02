package com.loadpilot.backend.service;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.security.CurrentUser;

/** Cree ou met a jour la copie locale (AppUser) d'un utilisateur authentifie. */
public interface AppUserSyncService {

    AppUser sync(CurrentUser currentUser);
}
