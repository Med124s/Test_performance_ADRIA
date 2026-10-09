package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.ProfileUpdateRequest;
import com.loadpilot.backend.dto.response.ProfileResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.exception.InvalidProfileException;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import com.loadpilot.backend.service.ProfileService;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    /** Ordre de priorite si plusieurs roles applicatifs : le plus large d'abord. */
    private static final List<String[]> APP_ROLES = List.of(
            new String[] {"ROLE_SUPER_ADMIN", "SUPER_ADMIN", "Super administrateur"},
            new String[] {"ROLE_PERFORMANCE_ENGINEER", "PERFORMANCE_ENGINEER", "Ingénieur performance"},
            new String[] {"ROLE_VIEWER", "VIEWER", "Observateur"});

    private final AppUserSyncService appUserSyncService;
    private final AppUserRepository appUserRepository;

    @Override
    @Transactional
    public ProfileResponse getProfile(CurrentUser currentUser) {
        AppUser appUser = appUserSyncService.sync(currentUser);
        // Identite/roles refletent toujours le Jwt en cours (source de verite du systeme
        // d'authentification) ; fuseau horaire et apparence sont les seules preferences locales.
        return toResponse(currentUser, appUser);
    }

    @Override
    @Transactional
    public ProfileResponse updateProfile(CurrentUser currentUser, ProfileUpdateRequest request) {
        AppUser appUser = appUserSyncService.sync(currentUser);
        if (request.timezone() != null) {
            validateTimezone(request.timezone());
            appUser.setTimezone(request.timezone());
        }
        if (request.theme() != null) {
            appUser.setTheme(request.theme());
        }
        appUser = appUserRepository.save(appUser);
        return toResponse(currentUser, appUser);
    }

    static ProfileResponse toResponse(CurrentUser currentUser, AppUser appUser) {
        String[] role = appRole(currentUser.roles());
        return new ProfileResponse(currentUser.subject(), currentUser.username(), currentUser.name(), currentUser.email(),
                currentUser.roles(), appUser.getTimezone(), role == null ? null : role[1], role == null ? null : role[2], appUser.getTheme());
    }

    /** Role applicatif parmi les roles du jeton (jamais offline_access, uma_authorization, default-roles-...). */
    static String[] appRole(java.util.Collection<String> roles) {
        if (roles == null) return null;
        for (String[] role : APP_ROLES) {
            if (roles.contains(role[0])) return role;
        }
        return null;
    }

    /** Jamais un fuseau invalide silencieusement accepte (meme verification que les planifications). */
    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new InvalidProfileException("Fuseau horaire invalide : \"" + timezone
                    + "\" (attendu un identifiant IANA, ex: Africa/Casablanca, UTC).");
        }
    }
}
