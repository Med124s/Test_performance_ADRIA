package com.loadpilot.backend.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controleur reserve aux tests de securite (Phase 4) - existe UNIQUEMENT
 * sous src/test/java, jamais empaquete dans l'application reelle. Sert a
 * verifier SecurityFilterChain / JwtAuthConverter / 401 / 403 / @PreAuthorize
 * avant que les vrais controleurs metier (Applications, Scenarios...)
 * n'existent (Phase 6+) - voir SecurityConfigTest.
 */
@RestController
class SecurityTestController {

    @GetMapping("/api/test/admin-only")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String adminOnly() {
        return "admin";
    }

    @GetMapping("/api/test/engineer-or-admin")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PERFORMANCE_ENGINEER')")
    public String engineerOrAdmin() {
        return "engineer-or-admin";
    }
}
