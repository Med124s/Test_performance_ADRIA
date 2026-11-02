package com.loadpilot.backend.security;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Origines CORS autorisees (app.cors.allowed-origins). */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** Vide par defaut : aucune origine autorisee. */
    private List<String> allowedOrigins = new ArrayList<>();
}
