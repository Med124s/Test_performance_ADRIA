package com.loadpilot.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentation OpenAPI / Swagger de l'API, avec authentification par jeton JWT. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI loadPilotOpenApi(@org.springframework.beans.factory.annotation.Value("${app.version}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("LoadPilot API")
                        .description("API REST de LoadPilot, plateforme de test de performance/charge : "
                                + "gestion des Applications cibles, Scenarios, Steps HTTP, Executions reelles, "
                                + "Metrics, Dashboard et Audit. Authentification via JWT Keycloak (OAuth2 Resource Server).")
                        .version(version))
                .servers(List.of(new Server().url("http://localhost:8080").description("Local (dev)")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT emis par Keycloak (voir spring.security.oauth2.resourceserver.jwt.issuer-uri) - "
                                        + "coller uniquement le token, sans le prefixe \"Bearer \".")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
