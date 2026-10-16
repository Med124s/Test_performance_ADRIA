package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.service.NotificationService;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Page « Toutes mes notifications » : pagination cote serveur (20 par defaut, 20 / 50 / 100),
 * filtres lues / non lues et type combines a la pagination, tri stable sans doublon entre pages.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationPaginationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private NotificationService notificationService;
    @Autowired private AppUserRepository appUserRepository;
    @MockBean private JwtDecoder jwtDecoder;

    private static final NotificationType[] CYCLE = {
        NotificationType.EXECUTION_SUCCESS, NotificationType.EXECUTION_FAILED, NotificationType.SCHEDULE_REGRESSION,
        NotificationType.SCHEDULE_TRIGGERED, NotificationType.EXECUTION_THRESHOLDS_EXCEEDED,
    };

    private static RequestPostProcessor asUser(String subject) {
        return jwt().jwt(b -> b.subject(subject).claim("preferred_username", subject))
                .authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
    }

    /** 45 notifications : 9 de chaque type du cycle ; les 10 premieres creees sont ensuite lues. */
    private RequestPostProcessor userWith45Notifications() throws Exception {
        String subject = "notif-page-" + UUID.randomUUID();
        RequestPostProcessor user = asUser(subject);
        mockMvc.perform(get("/api/profile").with(user)).andExpect(status().isOk());
        UUID appUserId = appUserRepository.findByKeycloakSubject(subject).orElseThrow().getId();
        for (int i = 0; i < 45; i++) {
            notificationService.create(appUserId, CYCLE[i % CYCLE.length], "Notification " + i, null, null, null);
            // Dates distinctes : l'ordre « plus recente d'abord » attendu par le test est alors certain.
            Thread.sleep(2);
        }
        JsonNode oldest = page(get("/api/notifications").param("size", "100"), user).get("content");
        for (int i = 0; i < 10; i++) {
            String id = oldest.get(oldest.size() - 1 - i).get("id").asText();
            mockMvc.perform(patch("/api/notifications/" + id + "/read").with(user)).andExpect(status().isOk());
        }
        return user;
    }

    private JsonNode page(MockHttpServletRequestBuilder request, RequestPostProcessor user) throws Exception {
        String body = mockMvc.perform(request.with(user)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void pagination_20ParDefaut_pagesSansDoublon_taillesAuChoix() throws Exception {
        RequestPostProcessor user = userWith45Notifications();

        JsonNode first = page(get("/api/notifications"), user);
        assertThat(first.get("size").asInt()).isEqualTo(20);
        assertThat(first.get("content").size()).isEqualTo(20);
        assertThat(first.get("totalElements").asLong()).isEqualTo(45);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("content").get(0).get("title").asText()).isEqualTo("Notification 44");

        Set<String> ids = new HashSet<>();
        for (int p = 0; p < 3; p++) {
            JsonNode content = page(get("/api/notifications").param("page", String.valueOf(p)), user).get("content");
            content.forEach(n -> ids.add(n.get("id").asText()));
            assertThat(content.size()).isEqualTo(p < 2 ? 20 : 5);
        }
        assertThat(ids).as("chaque notification sur une seule page").hasSize(45);

        assertThat(page(get("/api/notifications").param("size", "50"), user).get("content").size()).isEqualTo(45);
        JsonNode hundred = page(get("/api/notifications").param("size", "100"), user);
        assertThat(hundred.get("totalPages").asInt()).isEqualTo(1);
        // Hors bornes : ramene a 1..100 (jamais une liste geante ni une erreur).
        assertThat(page(get("/api/notifications").param("size", "5000"), user).get("size").asInt()).isEqualTo(100);
        assertThat(page(get("/api/notifications").param("size", "0").param("page", "-3"), user).get("page").asInt()).isZero();
    }

    @Test
    void filtres_luesNonLues_etType_combinesALaPagination() throws Exception {
        RequestPostProcessor user = userWith45Notifications();

        JsonNode unread = page(get("/api/notifications").param("read", "false"), user);
        assertThat(unread.get("totalElements").asLong()).isEqualTo(35);
        assertThat(unread.get("totalPages").asInt()).isEqualTo(2);
        unread.get("content").forEach(n -> assertThat(n.get("read").asBoolean()).isFalse());
        assertThat(page(get("/api/notifications").param("read", "true"), user).get("totalElements").asLong()).isEqualTo(10);

        JsonNode failures = page(get("/api/notifications").param("type", "EXECUTION_FAILED").param("size", "5"), user);
        assertThat(failures.get("totalElements").asLong()).isEqualTo(9);
        assertThat(failures.get("totalPages").asInt()).isEqualTo(2);
        failures.get("content").forEach(n -> assertThat(n.get("type").asText()).isEqualTo("EXECUTION_FAILED"));

        // Plusieurs types a la fois + non lues + page 2.
        JsonNode combined = page(get("/api/notifications").param("type", "EXECUTION_FAILED", "SCHEDULE_REGRESSION")
                .param("read", "false").param("size", "5").param("page", "1"), user);
        long expected = page(get("/api/notifications").param("type", "EXECUTION_FAILED", "SCHEDULE_REGRESSION")
                .param("read", "false").param("size", "100"), user).get("content").size();
        assertThat(combined.get("totalElements").asLong()).isEqualTo(expected);
        assertThat(expected).isBetween(14L, 18L);
        combined.get("content").forEach(n -> {
            assertThat(n.get("type").asText()).isIn("EXECUTION_FAILED", "SCHEDULE_REGRESSION");
            assertThat(n.get("read").asBoolean()).isFalse();
        });

        mockMvc.perform(get("/api/notifications").param("type", "PAS_UN_TYPE").with(user)).andExpect(status().isBadRequest());
    }
}
