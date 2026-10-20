package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.service.report.pdf.ReportPdfService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Manque de memoire pendant une requete : reponse 503 claire (GlobalExceptionHandler), pas la page d'erreur generique. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OutOfMemoryHandlerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ReportPdfService reportPdfService;
    @MockBean private JwtDecoder jwtDecoder;

    @Test
    void outOfMemoryDuringARequest_returnsA503WithAClearMessage() throws Exception {
        when(reportPdfService.export(any())).thenThrow(new OutOfMemoryError("Java heap space"));
        MockHttpServletResponse response = mockMvc.perform(get("/api/executions/" + UUID.randomUUID() + "/report/pdf")
                .with(jwt().jwt(j -> j.subject("oom-viewer")).authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("message").asText()).startsWith("Mémoire du serveur insuffisante pour traiter cette demande");
    }
}
