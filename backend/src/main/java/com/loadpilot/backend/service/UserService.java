package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.CreateUserRequest;
import com.loadpilot.backend.dto.response.PasswordPolicyResponse;
import com.loadpilot.backend.dto.response.UserCreatedResponse;
import com.loadpilot.backend.dto.response.UserSummaryResponse;
import com.loadpilot.backend.enums.AppRole;
import java.util.List;

/**
 * "actorSubject" : sujet Keycloak (claim "sub") de l'administrateur qui fait
 * la demande - sert a lui interdire de retirer son propre role
 * Administrateur ou de desactiver son propre compte.
 */
public interface UserService {

    List<UserSummaryResponse> list();

    UserSummaryResponse updateRole(String actorSubject, String userId, AppRole role);

    UserSummaryResponse updateStatus(String actorSubject, String userId, boolean enabled);

    UserCreatedResponse create(CreateUserRequest request);

    /** Envoie l'e-mail Keycloak de reinitialisation du mot de passe. */
    void sendPasswordReset(String userId);

    /** Definit le mot de passe (temporary = changement impose a la prochaine connexion). */
    void setPassword(String userId, String password, boolean temporary);

    PasswordPolicyResponse passwordPolicy();
}
