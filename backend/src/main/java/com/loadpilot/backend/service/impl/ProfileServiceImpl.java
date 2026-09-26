package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.ProfileUpdateRequest;
import com.loadpilot.backend.dto.response.ProfileResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.exception.InvalidProfileException;
import com.loadpilot.backend.mapper.ProfileMapper;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import com.loadpilot.backend.service.ProfileService;
import java.time.DateTimeException;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    private final AppUserSyncService appUserSyncService;
    private final AppUserRepository appUserRepository;
    private final ProfileMapper profileMapper;

    @Override
    @Transactional
    public ProfileResponse getProfile(CurrentUser currentUser) {
        AppUser appUser = appUserSyncService.sync(currentUser);
        // Identite/roles refletent toujours le Jwt en cours (source de
        // verite Keycloak) ; "timezone" est la SEULE exception - une
        // preference reelle qui n'existe que dans la projection locale
        // (voir AppUser.timezone, jamais dans le Jwt).
        return profileMapper.toProfileResponse(currentUser, appUser.getTimezone());
    }

    @Override
    @Transactional
    public ProfileResponse updateProfile(CurrentUser currentUser, ProfileUpdateRequest request) {
        validateTimezone(request.timezone());
        AppUser appUser = appUserSyncService.sync(currentUser);
        appUser.setTimezone(request.timezone());
        appUser = appUserRepository.save(appUser);
        return profileMapper.toProfileResponse(currentUser, appUser.getTimezone());
    }

    /** Jamais un fuseau invalide silencieusement accepte - ZoneId.of() est
     * la meme verification deja utilisee par ScheduleNextRunCalculator
     * (P1-B) pour les ScheduledExecution, reappliquee ici pour la meme
     * garantie. */
    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new InvalidProfileException("Fuseau horaire invalide : \"" + timezone
                    + "\" (attendu un identifiant IANA, ex: Africa/Casablanca, UTC).");
        }
    }
}
