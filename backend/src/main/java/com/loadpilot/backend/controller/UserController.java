package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.request.UpdateUserRoleRequest;
import com.loadpilot.backend.dto.request.UpdateUserStatusRequest;
import com.loadpilot.backend.dto.response.UserSummaryResponse;
import com.loadpilot.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/users - administration reelle des comptes Keycloak (Phase 25).
 * Keycloak est l'unique source de verite (identite/roles/etat active-
 * desactive) ; aucun mot de passe ne transite jamais par cette API.
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
    @Operation(summary = "Lister les utilisateurs Keycloak reels")
    public List<UserSummaryResponse> list() {
        return userService.list();
    }

    @PutMapping("/{id}/role")
    @Operation(summary = "Modifier le role reel d'un utilisateur", description = "Modifie reellement les realm roles Keycloak de l'utilisateur (jamais un champ local).")
    @ApiResponse(responseCode = "404", description = "Utilisateur ou role Keycloak introuvable")
    public UserSummaryResponse updateRole(@PathVariable String id, @Valid @RequestBody UpdateUserRoleRequest request) {
        return userService.updateRole(id, request.role());
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "Activer/desactiver reellement un utilisateur", description = "Modifie reellement le champ 'enabled' du compte Keycloak.")
    @ApiResponse(responseCode = "404", description = "Utilisateur introuvable")
    public UserSummaryResponse updateStatus(@PathVariable String id, @Valid @RequestBody UpdateUserStatusRequest request) {
        return userService.updateStatus(id, request.enabled());
    }
}
