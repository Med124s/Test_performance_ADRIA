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

/**
 * Configuration OpenAPI/Swagger (Phase 13) - documente l'API REST existante
 * telle qu'elle est reellement implementee, ne cree AUCUN endpoint, AUCUNE
 * authentification supplementaire.
 *
 * Le schema de securite "bearerAuth" documente le JWT deja emis par
 * Keycloak et deja verifie par le Resource Server (voir SecurityConfig,
 * JwtAuthConverter) - Swagger UI se contente de permettre de coller ce JWT
 * dans l'en-tete Authorization pour tester les endpoints, il ne le genere
 * jamais et ne cree aucun UserDetailsService/mot de passe local.
 *
 * Contact/licence delibrement absents : aucune information de ce type n'est
 * connue/confirmee a ce stade du projet - mieux vaut l'omettre que
 * l'inventer (voir Phase 13, section 9).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI loadPilotOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LoadPilot API")
                        .description("API REST de LoadPilot, plateforme de test de performance/charge : "
                                + "gestion des Applications cibles, Scenarios, Steps HTTP, Executions reelles, "
                                + "Metrics, Dashboard et Audit. Authentification via JWT Keycloak (OAuth2 Resource Server).")
                        .version("1.0.0"))
                // Seul serveur reellement confirme par la configuration du
                // projet (aucun server.port ne surcharge le port par defaut
                // de Spring Boot, voir application.yml/application-dev.yml) -
                // aucune URL de production inventee.
                .servers(List.of(new Server().url("http://localhost:8080").description("Local (dev)")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT emis par Keycloak (voir spring.security.oauth2.resourceserver.jwt.issuer-uri) - "
                                        + "coller uniquement le token, sans le prefixe \"Bearer \".")))
                // Requirement global : tous les endpoints sont proteges par
                // defaut (anyRequest().authenticated(), voir SecurityConfig) -
                // seuls /actuator/health et les URLs Swagger elles-memes sont
                // publiques (permitAll cote SecurityConfig), une exception de
                // securite reseau, pas une exception de documentation.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
