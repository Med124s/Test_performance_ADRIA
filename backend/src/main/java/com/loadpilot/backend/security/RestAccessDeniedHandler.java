package com.loadpilot.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 403 - utilisateur authentifie mais role/permission insuffisant (que la
 * verification vienne de authorizeHttpRequests() ou d'un @PreAuthorize
 * methode). JSON coherent avec le futur format d'erreur global.
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter responseWriter;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        responseWriter.write(
                response,
                HttpStatus.FORBIDDEN,
                "Vous etes authentifie mais vous n'avez pas les permissions necessaires pour cette action.",
                request.getRequestURI()
        );
    }
}
