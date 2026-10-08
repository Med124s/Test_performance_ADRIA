package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.CreateUserRequest;
import com.loadpilot.backend.dto.request.SetPasswordRequest;
import com.loadpilot.backend.dto.response.PasswordPolicyResponse;
import com.loadpilot.backend.dto.request.UpdateUserRoleRequest;
import com.loadpilot.backend.dto.request.UpdateUserStatusRequest;
import com.loadpilot.backend.dto.response.UserCreatedResponse;
import com.loadpilot.backend.dto.response.UserSummaryResponse;
import com.loadpilot.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/users - administration reelle des comptes Keycloak (Phase 25).
 * Keycloak est l'unique source de verite (identite/roles/etat active-
 * desactive) ; aucun mot de passe ne transite jamais par cette API (creation
 * et reinitialisation passent par un e-mail Keycloak).
 *
 * Reserve a SUPER_ADMIN uniquement (@PreAuthorize au niveau de la classe,
 * comme AuditLogController) : l'administration des comptes/roles est
 * l'operation la plus sensible du systeme (elle peut notamment accorder
 * ROLE_SUPER_ADMIN a n'importe qui) - contrairement a l'audit (SUPER_ADMIN
 * + PERFORMANCE_ENGINEER), aucune raison metier ne justifie d'y donner
 * acces a PERFORMANCE_ENGINEER (voir rapport Phase 25).
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Users", description = "Administration reelle des utilisateurs Keycloak (identite/statut/role) - reserve a SUPER_ADMIN")
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "Lister les utilisateurs Keycloak reels",
            description = "Avec la derniere connexion (evenements LOGIN Keycloak) et le nombre d'executions lancees.")
    public List<UserSummaryResponse> list() {
        return userService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Creer un utilisateur Keycloak",
            description = "Cree le compte avec son role. Avec 'password' : mot de passe defini tout de suite (temporaryPassword = "
                    + "changement impose a la 1re connexion). Sans : Keycloak envoie un e-mail pour le choisir. "
                    + "Le mot de passe n'est jamais renvoye ni journalise.")
    @ApiResponse(responseCode = "409", description = "Nom d'utilisateur ou e-mail deja utilise")
    public UserCreatedResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PutMapping("/{id}/role")
    @Operation(summary = "Modifier le role reel d'un utilisateur", description = "Modifie reellement les realm roles Keycloak de l'utilisateur (jamais un champ local).")
    @ApiResponse(responseCode = "404", description = "Utilisateur ou role Keycloak introuvable")
    @ApiResponse(responseCode = "409", description = "Un administrateur ne peut pas retirer son propre role Administrateur")
    public UserSummaryResponse updateRole(@PathVariable String id, @Valid @RequestBody UpdateUserRoleRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return userService.updateRole(jwt.getSubject(), id, request.role());
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Activer/desactiver reellement un utilisateur", description = "Modifie reellement le champ 'enabled' du compte Keycloak.")
    @ApiResponse(responseCode = "404", description = "Utilisateur introuvable")
    @ApiResponse(responseCode = "409", description = "Un administrateur ne peut pas desactiver son propre compte")
    public UserSummaryResponse updateStatus(@PathVariable String id, @Valid @RequestBody UpdateUserStatusRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        return userService.updateStatus(jwt.getSubject(), id, request.enabled());
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Envoyer l'e-mail de reinitialisation du mot de passe",
            description = "Keycloak envoie a l'utilisateur un lien pour choisir un nouveau mot de passe (SMTP du realm).")
    @ApiResponse(responseCode = "400", description = "Utilisateur sans e-mail ou compte desactive")
    @ApiResponse(responseCode = "502", description = "Keycloak n'a pas pu envoyer l'e-mail (SMTP)")
    public void resetPassword(@PathVariable String id) {
        userService.sendPasswordReset(id);
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Definir le mot de passe d'un utilisateur",
            description = "Transmis a Keycloak (politique du realm verifiee), jamais enregistre ni renvoye ni journalise. "
                    + "temporary = changement impose a la prochaine connexion.")
    @ApiResponse(responseCode = "400", description = "Mot de passe refuse par la politique (message en francais)")
    public void setPassword(@PathVariable String id, @Valid @RequestBody SetPasswordRequest request) {
        userService.setPassword(id, request.password(), request.temporary());
    }

    @GetMapping("/password-policy")
    @Operation(summary = "Politique de mot de passe du realm", description = "Pour la validation en direct dans le formulaire.")
    public PasswordPolicyResponse passwordPolicy() {
        return userService.passwordPolicy();
    }
}
