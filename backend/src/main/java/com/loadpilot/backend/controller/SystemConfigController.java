package com.loadpilot.backend.controller;

import com.loadpilot.backend.dto.response.SystemConfigResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Limites de charge du serveur (lecture seule), reservees au Super administrateur. */
@RestController
@RequestMapping("/api/system/config")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "System Config", description = "Limites de charge en vigueur sur le serveur (lecture seule, reserve a SUPER_ADMIN)")
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
    @Operation(summary = "Limites operationnelles actuelles", description = "Valeurs en vigueur (variables d'environnement ou valeurs par defaut), non modifiables via cette API.")
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
