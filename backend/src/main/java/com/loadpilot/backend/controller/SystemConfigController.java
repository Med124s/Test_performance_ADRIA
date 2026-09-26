package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.response.SystemConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/system/config — LECTURE SEULE (aucun PUT/PATCH n'existe ni ne
 * doit exister ici, voir SystemConfigResponse) des limites operationnelles
 * reellement actives sur ce backend.
 *
 * P1-D (voir rapport, section "Configurations") — decision explicite :
 * ces limites restent pilotees par variable d'environnement (12-factor,
 * deja le choix de P0-B/P1-B) et ne deviennent PAS une configuration
 * mutable en base : (1) RunningExecutionRegistry les recoit par injection
 * de constructeur au demarrage - les rendre editables a chaud exigerait de
 * transformer un composant deja durci (verrous de capacite, voir P0-B) en
 * etat mutable partage, un risque non justifie par un besoin reel ; (2) le
 * prompt interdit explicitement un magasin de configuration cle/valeur
 * generique. Reserve a SUPER_ADMIN : ce sont des parametres d'infrastructure,
 * pas une donnee operationnelle (execution/scenario) au sens du reste de
 * l'API.
 */
@RestController
@RequestMapping("/api/system/config")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "System Config", description = "Limites operationnelles reelles actuellement actives (lecture seule, reserve a SUPER_ADMIN)")
public class SystemConfigController {

    @Value("${app.execution.max-virtual-users-per-execution}")
    private int maxVirtualUsersPerExecution;

    @Value("${app.execution.max-global-virtual-users}")
    private int maxGlobalVirtualUsers;

    @Value("${app.execution.max-concurrent-executions}")
    private int maxConcurrentExecutions;

    @Value("${app.execution.timeout-seconds}")
    private int executionTimeoutSeconds;

    @Value("${app.availability.timeout-seconds}")
    private int availabilityTimeoutSeconds;

    @Value("${app.scheduler.poll-interval-ms}")
    private long schedulerPollIntervalMs;

    @GetMapping
    @Operation(summary = "Limites operationnelles actuelles", description = "Valeurs REELLEMENT actives (variables d'environnement/valeurs par defaut), jamais modifiables via cette API.")
    public SystemConfigResponse getConfig() {
        return new SystemConfigResponse(
                maxVirtualUsersPerExecution,
                maxGlobalVirtualUsers,
                maxConcurrentExecutions,
                executionTimeoutSeconds,
                availabilityTimeoutSeconds,
                schedulerPollIntervalMs);
    }
}
