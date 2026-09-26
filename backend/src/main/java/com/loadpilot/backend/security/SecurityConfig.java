package com.loadpilot.backend.security;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Fondation de securite (Phase 4) : Resource Server OAuth2/JWT (Keycloak),
 * sessions stateless, CSRF desactive (API REST pure), Method Security
 * (@PreAuthorize) activee pour les futurs controleurs metier.
 *
 * Aucun UserDetailsService/mot de passe local : l'identite et les roles
 * viennent exclusivement du JWT Keycloak (voir JwtAuthConverter).
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            // Health check technique minimal (voir application.yml,
            // management.endpoints.web.exposure.include=health) - aucune
            // information sensible exposee (show-details=never). Phase 13 :
            // "/actuator/info" retire, non expose par Actuator (voir
            // application.yml) - aucune raison de le laisser en permitAll.
            "/actuator/health",
            // Documentation API (Phase 13) - PUBLIQUE en tant que
            // documentation uniquement : les endpoints metier eux-memes
            // restent proteges par anyRequest().authenticated() ci-dessous,
            // Swagger UI ne fait qu'appeler ces memes endpoints proteges
            // depuis le navigateur de l'utilisateur (JWT colle manuellement).
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
    };

    private final JwtAuthConverter jwtAuthConverter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final CorsProperties corsProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated()
                )
                // Entry point explicitement fixe ici (et pas seulement via
                // exceptionHandling) : sans ca, le Resource Server retombe
                // sur son BearerTokenAuthenticationEntryPoint par defaut
                // (challenge WWW-Authenticate) plutot que notre JSON.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                )
                // Le 403 (authorizeHttpRequests ET @PreAuthorize methode)
                // passe par ExceptionTranslationFilter, configure ici.
                .exceptionHandling(ex -> ex.accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
