package com.loadpilot.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 401 - utilisateur non authentifie : token absent, invalide ou expire.
 * Remplace la reponse HTML/texte par defaut de Spring Security par un JSON
 * coherent avec le futur format d'erreur global (voir exception.ErrorResponse).
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter responseWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        responseWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                "Authentification requise : token absent, invalide ou expire.",
                request.getRequestURI()
        );
    }
}
