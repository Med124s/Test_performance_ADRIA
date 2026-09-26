package com.loadpilot.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Ecrit une reponse d'erreur JSON coherente avec le format global attendu
 * (voir exception.ErrorResponse) - partage par RestAuthenticationEntryPoint
 * (401) et RestAccessDeniedHandler (403) pour ne pas dupliquer la
 * serialisation.
 */
@Component
@RequiredArgsConstructor
class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    void write(HttpServletResponse response, HttpStatus status, String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(status, message, path));
    }
}
