package com.loadpilot.backend.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controleur de test de la securite (present seulement dans src/test) : 401, 403 et roles. */
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
