package com.loadpilot.backend.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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

/**
 * Verifie /api/audit-logs : securite (VIEWER exclu, voir Phase 12, section
 * 13), routage /stats et /export non avales par /{id}, filtres, pagination,
 * export CSV. Les vraies entrees d'audit exploitees ici proviennent d'une
 * vraie action metier (POST /api/applications), jamais fabriquees a la main.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    private String createApplicationAndGetName() throws Exception {
        String name = "audit-log-controller-app-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(new ApplicationRequest(name, "Description", "http://localhost:1"));
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return name;
    }

    // ------------------------------------------------------------
    // Securite - GET /api/audit-logs
    // ------------------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_asViewer_returns403() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void list_asEngineer_returns200() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(AS_ENGINEER)).andExpect(status().isOk());
    }

    @Test
    void list_asSuperAdmin_returns200() throws Exception {
        mockMvc.perform(get("/api/audit-logs").with(AS_SUPER_ADMIN)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------
    // Securite - GET /api/audit-logs/{id}
    // ------------------------------------------------------------

    @Test
    void getById_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/audit-logs/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void getById_asViewer_returns403() throws Exception {
        mockMvc.perform(get("/api/audit-logs/" + UUID.randomUUID()).with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void getById_unknownId_asSuperAdmin_returns404() throws Exception {
        mockMvc.perform(get("/api/audit-logs/" + UUID.randomUUID()).with(AS_SUPER_ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getById_invalidUuid_asSuperAdmin_returns400() throws Exception {
        mockMvc.perform(get("/api/audit-logs/not-a-uuid").with(AS_SUPER_ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // ------------------------------------------------------------
    // Securite + routage - GET /api/audit-logs/stats
    // ------------------------------------------------------------

    @Test
    void stats_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/audit-logs/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void stats_asViewer_returns403() throws Exception {
        mockMvc.perform(get("/api/audit-logs/stats").with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void stats_asEngineer_returns200() throws Exception {
        mockMvc.perform(get("/api/audit-logs/stats").with(AS_ENGINEER)).andExpect(status().isOk());
    }

    @Test
    void stats_routesCorrectly_isNeverInterpretedAsAnId() throws Exception {
        // Si "stats" etait avale par /{id}, Spring tenterait de le convertir
        // en UUID et renverrait 400 (voir getById_invalidUuid ci-dessus) au
        // lieu du vrai contenu de AuditStatsResponse.
        mockMvc.perform(get("/api/audit-logs/stats").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalActions").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.successfulActions").exists())
                .andExpect(jsonPath("$.failedActions").exists());
    }

    // ------------------------------------------------------------
    // Securite + routage - GET /api/audit-logs/export
    // ------------------------------------------------------------

    @Test
    void export_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/audit-logs/export")).andExpect(status().isUnauthorized());
    }

    @Test
    void export_asViewer_returns403() throws Exception {
        mockMvc.perform(get("/api/audit-logs/export").with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void export_routesCorrectly_returnsCsvNotAnIdError() throws Exception {
        mockMvc.perform(get("/api/audit-logs/export").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"audit-logs.csv\""));
    }

    @Test
    void export_asSuperAdmin_bodyStartsWithCsvHeaderRow() throws Exception {
        String body = mockMvc.perform(get("/api/audit-logs/export").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .startsWith("id,userId,username,action,module,date,ip,result,description");
    }

    // ------------------------------------------------------------
    // Filtres / pagination - donnees reelles issues d'une vraie action
    // ------------------------------------------------------------

    @Test
    void list_filterByModuleAndAction_findsTheRealApplicationCreationAudit() throws Exception {
        String appName = createApplicationAndGetName();

        mockMvc.perform(get("/api/audit-logs")
                        .param("module", "APPLICATION")
                        .param("action", "CREATE")
                        .param("result", "SUCCESS")
                        .param("size", "200")
                        .with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.description=='Application created: " + appName + "')]").exists());
    }

    @Test
    void list_paginationParams_areRespectedInResponse() throws Exception {
        mockMvc.perform(get("/api/audit-logs").param("page", "0").param("size", "5").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(5)));
    }

    @Test
    void list_filterByNonexistentUserId_returnsEmptyContent() throws Exception {
        mockMvc.perform(get("/api/audit-logs").param("userId", "no-such-user-" + UUID.randomUUID()).with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
