package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppUserSyncServiceImpl implements AppUserSyncService {

    private final AppUserRepository appUserRepository;

    /**
     * Cree la projection locale au premier login, ou rafraichit
     * username/name/email a chaque appel si elle existe deja - Keycloak
     * restant la seule source de verite pour ces informations. Aucune
     * gestion de mot de passe ici.
     */
    @Override
    @Transactional
    public AppUser sync(CurrentUser currentUser) {
        AppUser appUser = appUserRepository.findByKeycloakSubject(currentUser.subject())
                .orElseGet(() -> AppUser.builder()
                        .keycloakSubject(currentUser.subject())
                        .enabled(true)
                        .build());

        appUser.setUsername(currentUser.username());
        appUser.setName(currentUser.name());
        appUser.setEmail(currentUser.email());

        return appUserRepository.save(appUser);
    }
}
