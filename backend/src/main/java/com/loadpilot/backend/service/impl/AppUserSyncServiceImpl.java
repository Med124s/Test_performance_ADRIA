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

    /** Cree la copie locale a la premiere connexion, puis met a jour username, nom et e-mail a chaque appel. */
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
