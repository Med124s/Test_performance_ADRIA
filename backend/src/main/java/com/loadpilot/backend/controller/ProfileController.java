package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.ProfileUpdateRequest;
import com.loadpilot.backend.dto.response.ProfileResponse;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/profile - protege par le SecurityFilterChain existant
 * (anyRequest().authenticated(), aucun permitAll ici) : requete sans token
 * valide -> 401 avant meme d'atteindre ce controleur.
 *
 * Ne fait aucun acces base de donnees/logique metier directement (voir
 * ProfileService) - se contente de construire l'abstraction CurrentUser a
 * partir du contexte de securite et de deleguer.
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "Profil de l'utilisateur authentifie (projection locale AppUser, aucun mot de passe)")
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    @Operation(summary = "Profil de l'utilisateur courant", description = "Identite/roles derives du JWT Keycloak, plus la preference de fuseau horaire reellement persistee ; 401 si non authentifie.")
    public ProfileResponse getProfile(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(jwt, authentication);
        return profileService.getProfile(currentUser);
    }

    /**
     * P1-D — ouvert a tout role authentifie (self-service, jamais une
     * action sur la ressource d'un autre) : seul le fuseau horaire est
     * modifiable ici, jamais username/name/email (proprietes de Keycloak,
     * voir ProfileServiceImpl).
     */
    @PatchMapping
    @Operation(summary = "Mettre a jour ma preference de fuseau horaire", description = "Seul champ modifiable : timezone (identifiant IANA). username/name/email restent la propriete de Keycloak, jamais modifiables ici.")
    @ApiResponse(responseCode = "400", description = "Fuseau horaire invalide")
    public ProfileResponse updateProfile(@Valid @RequestBody ProfileUpdateRequest request,
                                          @AuthenticationPrincipal Jwt jwt,
                                          Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(jwt, authentication);
        return profileService.updateProfile(currentUser, request);
    }
}
