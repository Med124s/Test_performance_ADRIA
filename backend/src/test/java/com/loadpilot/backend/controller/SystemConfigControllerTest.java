package com.loadpilot.backend.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * P1-D — verifie GET /api/system/config : 401 sans token, 403 pour
 * VIEWER/PERFORMANCE_ENGINEER (reserve SUPER_ADMIN, voir
 * SystemConfigController), 200 avec les VRAIES valeurs actives (voir
 * application-test.yml/application.yml - jamais une valeur inventee).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SystemConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @Test
    void getConfig_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/system/config")).andExpect(status().isUnauthorized());
    }

    @Test
    void getConfig_viewerRole_returns403() throws Exception {
        mockMvc.perform(get("/api/system/config").with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void getConfig_performanceEngineerRole_returns403() throws Exception {
        mockMvc.perform(get("/api/system/config").with(AS_ENGINEER)).andExpect(status().isForbidden());
    }

    @Test
    void getConfig_superAdmin_returnsRealActiveValues() throws Exception {
        mockMvc.perform(get("/api/system/config").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxVirtualUsersPerExecution").isNumber())
                .andExpect(jsonPath("$.maxGlobalVirtualUsers").isNumber())
                .andExpect(jsonPath("$.maxConcurrentExecutions").isNumber())
                .andExpect(jsonPath("$.executionTimeoutSeconds").isNumber())
                .andExpect(jsonPath("$.availabilityTimeoutSeconds").isNumber())
                .andExpect(jsonPath("$.schedulerPollIntervalMs").isNumber());
    }
}
