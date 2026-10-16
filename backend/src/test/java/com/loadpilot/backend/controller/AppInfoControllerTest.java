package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Version de l'application : celle du pom.xml (seule definition), lue par la barre laterale et l'OpenAPI. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppInfoControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private JwtDecoder jwtDecoder;

    /** <version> du projet dans pom.xml (pas celle du parent Spring Boot). */
    private static String pomVersion() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<artifactId>backend</artifactId>.*?<version>([^<]+)</version>", Pattern.DOTALL).matcher(pom);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void version_isThePomVersion_readByAnyUser_sameInOpenApi() throws Exception {
        String expected = pomVersion();
        assertThat(expected).as("premiere version du projet").isEqualTo("1.0.0");

        String body = mockMvc.perform(get("/api/version").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("name").asText()).isEqualTo("LoadPilot");
        assertThat(json.get("version").asText()).as("recopiee depuis le pom au build, jamais @project.version@").isEqualTo(expected);

        assertThat(mockMvc.perform(get("/api/version")).andReturn().getResponse().getStatus()).isEqualTo(401);

        String openApi = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(openApi).get("info").get("version").asText()).isEqualTo(expected);
    }
}
