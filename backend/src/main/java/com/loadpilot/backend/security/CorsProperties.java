package com.loadpilot.backend.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origines CORS autorisees, externalisees (jamais codees en dur, jamais de
 * wildcard "*") - voir application-dev.yml pour la valeur de developpement
 * (frontend React local). Liee au SecurityFilterChain via
 * {@code @EnableConfigurationProperties(CorsProperties.class)} sur
 * SecurityConfig.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /**
     * Vide par defaut : un environnement qui ne definit pas explicitement
     * "app.cors.allowed-origins" n'autorise donc AUCUNE origine cross-origin,
     * plutot que de retomber sur un comportement permissif par accident.
     */
    private List<String> allowedOrigins = new ArrayList<>();
}
