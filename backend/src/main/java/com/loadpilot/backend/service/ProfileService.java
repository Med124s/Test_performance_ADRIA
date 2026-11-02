package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.ProfileUpdateRequest;
import com.loadpilot.backend.dto.response.ProfileResponse;
import com.loadpilot.backend.security.CurrentUser;

public interface ProfileService {

    /** Profil de l'utilisateur authentifie ; met aussi a jour sa copie locale (AppUser). */
    ProfileResponse getProfile(CurrentUser currentUser);

    /** Met a jour les preferences du profil (fuseau horaire, apparence). */
    ProfileResponse updateProfile(CurrentUser currentUser, ProfileUpdateRequest request);

    /** Prenom, nom et e-mail du compte connecte (Super administrateur seulement). */
    ProfileResponse updateIdentity(CurrentUser currentUser, com.loadpilot.backend.dto.request.ProfileIdentityRequest request);
}
