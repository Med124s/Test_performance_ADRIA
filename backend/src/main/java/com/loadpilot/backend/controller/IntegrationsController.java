package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.response.IntegrationsResponse;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.service.IntegrationsService;
import com.loadpilot.backend.service.mail.MailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Parametres > Integrations - reserve a SUPER_ADMIN, aucun secret renvoye. */
@RestController
@RequestMapping("/api/settings/integrations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Integrations", description = "Serveur e-mail et monitoring CPU / RAM - reserve a SUPER_ADMIN")
public class IntegrationsController {

    private final IntegrationsService integrationsService;

    @GetMapping
    @Operation(summary = "Etat des integrations", description = "Serveur e-mail (connexion reelle), serveur e-mail du systeme d'authentification, monitoring de chaque application.")
    public IntegrationsResponse integrations() {
        return integrationsService.integrations();
    }

    @PostMapping("/mail-test")
    @Operation(summary = "Envoyer un e-mail de test", description = "A l'adresse du compte connecte. Journalise.")
    public Map<String, String> mailTest(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Votre compte n'a pas d'adresse e-mail.");
        }
        try {
            return Map.of("sentTo", integrationsService.sendTestEmail(email, jwt.getClaimAsString("preferred_username")));
        } catch (MailService.MailException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
