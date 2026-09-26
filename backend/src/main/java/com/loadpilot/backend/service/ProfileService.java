package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.ProfileUpdateRequest;
import com.loadpilot.backend.dto.response.ProfileResponse;
import com.loadpilot.backend.security.CurrentUser;

public interface ProfileService {

    /**
     * Construit le profil de l'utilisateur authentifie (identite + roles
     * issus du Jwt), et synchronise au passage sa projection locale
     * (AppUser) - voir l'implementation pour le detail de cette
     * synchronisation.
     */
    ProfileResponse getProfile(CurrentUser currentUser);

    /**
     * P1-D — met a jour la SEULE preference reellement possedee par
     * l'utilisateur (timezone, voir ProfileUpdateRequest).
     * @throws com.loadpilot.backend.exception.InvalidProfileException si le fuseau horaire n'est pas un identifiant IANA valide.
     */
    ProfileResponse updateProfile(CurrentUser currentUser, ProfileUpdateRequest request);
}
