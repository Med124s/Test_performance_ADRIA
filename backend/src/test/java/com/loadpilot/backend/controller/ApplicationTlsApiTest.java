package com.loadpilot.backend.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loadpilot.backend.service.http.TestPki;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Autorite de certification d'une application : enregistree, relue (avec son resume), validee par l'API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationTlsApiTest {

    @TempDir
    static Path dir;

    private static String caPem;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

    @BeforeAll
    static void pki() throws Exception {
        caPem = TestPki.create(dir, "LoadPilot API CA").caPem;
    }

    private ObjectNode application(String name, String ca) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.put("url", "https://example.com/" + name);
        ObjectNode network = node.putObject("network");
        network.put("proxyMode", "INHERIT");
        if (ca != null) network.put("tlsCaCertificate", ca);
        return node;
    }

    @Test
    void ca_isSavedAndDescribed_unchangedWithoutNetwork_andClearedWhenEmptied() throws Exception {
        String created = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(application("tls-app", caPem))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.network.tlsCaCertificate").value(caPem.trim()))
                .andExpect(jsonPath("$.network.tlsCaCertificates[0].subject").value("CN=LoadPilot API CA"))
                .andExpect(jsonPath("$.network.tlsCaCertificates[0].expired").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        // Mise a jour sans section reseau : autorite conservee.
        ObjectNode withoutNetwork = application("tls-app", null);
        withoutNetwork.remove("network");
        mockMvc.perform(put("/api/applications/" + id).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withoutNetwork)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network.tlsCaCertificates.length()").value(1));

        // Champ vide : retour a la confiance de la JVM seule.
        mockMvc.perform(put("/api/applications/" + id).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(application("tls-app", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network.tlsCaCertificate").doesNotExist())
                .andExpect(jsonPath("$.network.tlsCaCertificates.length()").value(0));

        mockMvc.perform(get("/api/applications/" + id).with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network.tlsCaCertificates.length()").value(0));
    }

    @Test
    void privateKeyOrGarbage_isRefused() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(application("tls-key-app",
                                "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("clé privée")));
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(application("tls-bad-app", "pas un certificat"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("format PEM attendu")));
    }
}
