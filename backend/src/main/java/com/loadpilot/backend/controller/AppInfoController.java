package com.loadpilot.backend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Nom et version de l'application, affiches dans la barre laterale. */
@RestController
@Tag(name = "Application", description = "Nom et version de LoadPilot")
public class AppInfoController {

    private final String version;

    public AppInfoController(@Value("${app.version}") String version) {
        this.version = version;
    }

    @GetMapping("/api/version")
    @Operation(summary = "Version de LoadPilot", description = "name et version (celle du pom.xml).")
    public Map<String, String> version() {
        return Map.of("name", "LoadPilot", "version", version);
    }
}
