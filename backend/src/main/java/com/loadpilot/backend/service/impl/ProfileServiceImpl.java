package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.dto.request.ProfileIdentityRequest;
import com.loadpilot.backend.dto.request.ProfileUpdateRequest;
import com.loadpilot.backend.dto.response.ProfileResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.exception.ForbiddenActionException;
import com.loadpilot.backend.exception.InvalidProfileException;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.ProfileService;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.keycloak.KeycloakAdminClient;
import com.loadpilot.backend.service.keycloak.KeycloakUserRecord;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profil de l'utilisateur connecte.
 *  - Preferences (fuseau horaire, apparence) : tout utilisateur.
 *  - Identite (prenom, nom, e-mail) : Super administrateur seulement, dans le systeme
 *    d'authentification, journalisee avant -> apres.
 *  - Nom d'utilisateur, role, etat du compte : jamais modifiables depuis son profil.
 * Toute demande refusee est journalisee et repond 403 avec la raison.
 */
@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    /** Ordre de priorite si plusieurs roles applicatifs : le plus large d'abord. */
    private static final List<String[]> APP_ROLES = List.of(
            new String[] {"ROLE_SUPER_ADMIN", "SUPER_ADMIN", "Super administrateur"},
            new String[] {"ROLE_PERFORMANCE_ENGINEER", "PERFORMANCE_ENGINEER", "Ingénieur performance"},
            new String[] {"ROLE_VIEWER", "VIEWER", "Observateur"});

    static final String IDENTITY_ADMIN_ONLY = "Seul un Super administrateur peut modifier son nom et son e-mail.";
    static final String PROTECTED_FIELDS = "Votre nom d’utilisateur, votre rôle et l’état de votre compte ne sont pas modifiables depuis votre profil.";

    private final AppUserSyncService appUserSyncService;
    private final AppUserRepository appUserRepository;
    private final KeycloakAdminClient keycloak;
    private final AuditLogService auditLogService;
    private final jakarta.validation.Validator validator;

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
        if (request.touchesProtectedFields()) {
            throw refused(currentUser, PROTECTED_FIELDS);
        }
        if (request.touchesIdentity()) {
            if (!isSuperAdmin(currentUser)) throw refused(currentUser, IDENTITY_ADMIN_ONLY);
            throw new BadRequestException("Pour modifier votre nom ou votre e-mail, utilisez « Modifier » dans Mon compte.");
        }
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

    @Override
    @Transactional
    public ProfileResponse updateIdentity(CurrentUser currentUser, ProfileIdentityRequest request) {
        if (!isSuperAdmin(currentUser)) throw refused(currentUser, IDENTITY_ADMIN_ONLY);
        if (request.touchesProtectedFields()) throw refused(currentUser, PROTECTED_FIELDS);
        // Champs verifies APRES le role : un non-administrateur recoit toujours 403, jamais 400.
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            // Dans l'ordre du formulaire : prénom, nom, e-mail.
            List<String> order = List.of("firstName", "lastName", "email");
            throw new BadRequestException(violations.stream()
                    .sorted(java.util.Comparator.comparingInt((jakarta.validation.ConstraintViolation<?> v) -> order.indexOf(v.getPropertyPath().toString()))
                            .thenComparing(jakarta.validation.ConstraintViolation::getMessage))
                    .map(jakarta.validation.ConstraintViolation::getMessage)
                    .collect(java.util.stream.Collectors.joining(" ")));
        }

        String firstName = request.firstName().trim();
        String lastName = request.lastName().trim();
        String email = request.email().trim();
        KeycloakUserRecord before = keycloak.getUser(currentUser.subject());
        AuditEvent audit = AuditEvent.of(AuditAction.UPDATE, AuditModule.USER)
                .target(AuditTargetType.USER, currentUser.subject(), label(currentUser))
                .description("Modification de mon compte (nom et e-mail)")
                .change("Prénom", before.firstName(), firstName)
                .change("Nom", before.lastName(), lastName)
                .change("E-mail", before.email(), email);

        AppUser appUser = appUserSyncService.sync(currentUser);
        String fullName = (firstName + " " + lastName).trim();
        boolean unchanged = Objects.equals(before.firstName(), firstName) && Objects.equals(before.lastName(), lastName)
                && email.equalsIgnoreCase(Objects.toString(before.email(), ""));
        if (!unchanged) {
            try {
                keycloak.updateUserIdentity(currentUser.subject(), firstName, lastName, email);
            } catch (RuntimeException e) {
                auditLogService.record(audit.failed(e));
                throw e;
            }
            // Projection locale a jour tout de suite (le jeton, lui, change au prochain rafraichissement).
            appUser.setName(fullName);
            appUser.setEmail(email);
            appUser = appUserRepository.save(appUser);
            auditLogService.record(audit);
        }
        CurrentUser updated = new CurrentUser(currentUser.subject(), currentUser.username(), fullName, email,
                currentUser.roles(), firstName, lastName);
        return toResponse(updated, appUser);
    }

    /** Demande refusee : journalisee (echec, avec la raison) puis 403. */
    private ForbiddenActionException refused(CurrentUser currentUser, String reason) {
        auditLogService.record(AuditEvent.of(AuditAction.UPDATE, AuditModule.USER)
                .target(AuditTargetType.USER, currentUser.subject(), label(currentUser))
                .description("Modification de mon compte refusée")
                .failed(reason));
        return new ForbiddenActionException(reason);
    }

    private static String label(CurrentUser currentUser) {
        return currentUser.username() != null ? currentUser.username() : currentUser.subject();
    }

    private static boolean isSuperAdmin(CurrentUser currentUser) {
        return currentUser.roles() != null && currentUser.roles().contains("ROLE_SUPER_ADMIN");
    }

    static ProfileResponse toResponse(CurrentUser currentUser, AppUser appUser) {
        String[] role = appRole(currentUser.roles());
        return new ProfileResponse(currentUser.subject(), currentUser.username(), currentUser.name(), currentUser.email(),
                currentUser.roles(), appUser.getTimezone(), role == null ? null : role[1], role == null ? null : role[2], appUser.getTheme(),
                currentUser.firstName(), currentUser.lastName(), isSuperAdmin(currentUser));
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
