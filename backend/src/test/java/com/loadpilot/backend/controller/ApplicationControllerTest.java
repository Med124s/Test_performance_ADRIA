package com.loadpilot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.service.http.HttpAvailabilityChecker;
import com.loadpilot.backend.service.http.HttpAvailabilityResult;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Verifie le module Applications de bout en bout (SecurityFilterChain reel,
 * ApplicationController -> ApplicationServiceImpl -> ApplicationRepository
 * sur H2 reel) - seuls JwtDecoder et HttpAvailabilityChecker sont mockes
 * (aucun Keycloak reel, aucun appel HTTP reel necessaire pour ces tests -
 * voir JavaHttpClientAvailabilityCheckerTest pour la preuve d'un vrai appel
 * HTTP).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private HttpAvailabilityChecker availabilityChecker;

    private static final RequestPostProcessor AS_SUPER_ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    private static final RequestPostProcessor AS_ENGINEER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_PERFORMANCE_ENGINEER"));
    private static final RequestPostProcessor AS_VIEWER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));

    @BeforeEach
    void defaultAvailabilityStub() {
        // Comportement par defaut (reponse HTTP 200) pour les tests qui ne
        // s'interessent qu'a l'autorisation, pas au resultat du test lui-meme.
        when(availabilityChecker.check(anyString(), any(Duration.class), any()))
                .thenReturn(new HttpAvailabilityResult(true, 200, 42L, null));
    }

    private String validRequestJson(String name, String url) throws Exception {
        return objectMapper.writeValueAsString(new ApplicationRequest(name, "Description de test", url));
    }

    private String createApplicationAndGetId(String name) throws Exception {
        String body = validRequestJson(name, "http://example.com/" + name);
        String response = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    // ------------------------------------------------------------
    // Securite (1-9)
    // ------------------------------------------------------------

    @Test
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
    }

    @Test
    void create_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("App", "http://example.com")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_withViewerRole_returns200() throws Exception {
        mockMvc.perform(get("/api/applications").with(AS_VIEWER)).andExpect(status().isOk());
    }

    @Test
    void create_withViewerRole_returns403() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("App", "http://example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_withViewerRole_returns403() throws Exception {
        String id = createApplicationAndGetId("viewer-update-target");
        mockMvc.perform(put("/api/applications/" + id).with(AS_VIEWER).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("Renamed", "http://example.com/renamed")))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_withViewerRole_returns403() throws Exception {
        String id = createApplicationAndGetId("viewer-delete-target");
        mockMvc.perform(delete("/api/applications/" + id).with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void testAvailability_withViewerRole_returns403() throws Exception {
        String id = createApplicationAndGetId("viewer-test-target");
        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_VIEWER)).andExpect(status().isForbidden());
    }

    @Test
    void engineerRole_canListCreateUpdateAndTest() throws Exception {
        mockMvc.perform(get("/api/applications").with(AS_ENGINEER)).andExpect(status().isOk());

        String body = validRequestJson("engineer-app", "http://example.com/engineer");
        String response = mockMvc.perform(post("/api/applications").with(AS_ENGINEER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(put("/api/applications/" + id).with(AS_ENGINEER).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("engineer-app-renamed", "http://example.com/engineer2")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_ENGINEER)).andExpect(status().isOk());
    }

    @Test
    void engineerRole_cannotDelete() throws Exception {
        String id = createApplicationAndGetId("engineer-delete-forbidden");
        mockMvc.perform(delete("/api/applications/" + id).with(AS_ENGINEER)).andExpect(status().isForbidden());
    }

    @Test
    void superAdminRole_canListCreateUpdateDeleteAndTest() throws Exception {
        mockMvc.perform(get("/api/applications").with(AS_SUPER_ADMIN)).andExpect(status().isOk());

        String id = createApplicationAndGetId("admin-app");

        mockMvc.perform(put("/api/applications/" + id).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("admin-app-renamed", "http://example.com/admin2")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/applications/" + id).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------
    // Validation (10-14)
    // ------------------------------------------------------------

    @Test
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("", "http://example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void create_blankUrl_returns400() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("App", "")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_malformedUrl_returns400() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("App", "ceci-nest-pas-une-url")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------
    // CRUD (15-20)
    // ------------------------------------------------------------

    @Test
    void create_returnsCreatedApplicationWithNoStatusYet() throws Exception {
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("brand-new-app", "http://example.com/new")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("brand-new-app"))
                .andExpect(jsonPath("$.status").value(nullValue()))
                .andExpect(jsonPath("$.createdBy").exists())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void getById_returnsCreatedApplication() throws Exception {
        String id = createApplicationAndGetId("get-by-id-app");
        mockMvc.perform(get("/api/applications/" + id).with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void list_includesCreatedApplication() throws Exception {
        String id = createApplicationAndGetId("list-includes-app");
        mockMvc.perform(get("/api/applications").with(AS_VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]").exists());
    }

    @Test
    void update_changesFields() throws Exception {
        String id = createApplicationAndGetId("update-target-app");
        mockMvc.perform(put("/api/applications/" + id).with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("updated-name", "http://example.com/updated")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated-name"))
                .andExpect(jsonPath("$.url").value("http://example.com/updated"));
    }

    @Test
    void delete_removesApplication() throws Exception {
        String id = createApplicationAndGetId("delete-target-app");
        mockMvc.perform(delete("/api/applications/" + id).with(AS_SUPER_ADMIN)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/applications/" + id).with(AS_VIEWER)).andExpect(status().isNotFound());
    }

    @Test
    void getById_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/applications/" + java.util.UUID.randomUUID()).with(AS_VIEWER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ------------------------------------------------------------
    // Disponibilite (21, 22, 24) - le test bas niveau avec un vrai serveur
    // HTTP est dans JavaHttpClientAvailabilityCheckerTest.
    // ------------------------------------------------------------

    @Test
    void testAvailability_reachableTarget_setsConnectedStatus() throws Exception {
        String id = createApplicationAndGetId("connected-app");
        when(availabilityChecker.check(anyString(), any(Duration.class), any()))
                .thenReturn(new HttpAvailabilityResult(true, 200, 15L, null));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.httpStatus").value(200));

        mockMvc.perform(get("/api/applications/" + id).with(AS_VIEWER))
                .andExpect(jsonPath("$.status").value("CONNECTED"));
    }

    @Test
    void testAvailability_httpErrorResponse_setsFailedStatus() throws Exception {
        String id = createApplicationAndGetId("failed-app");
        when(availabilityChecker.check(anyString(), any(Duration.class), any()))
                .thenReturn(new HttpAvailabilityResult(true, 500, 20L, null));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.httpStatus").value(500));
    }

    @Test
    void testAvailability_technicalFailure_setsErrorStatus() throws Exception {
        String id = createApplicationAndGetId("error-app");
        when(availabilityChecker.check(anyString(), any(Duration.class), any()))
                .thenReturn(new HttpAvailabilityResult(false, null, 5000L, "Connection refused"));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.httpStatus").value(nullValue()));
    }


    // ------------------------------------------------------------
    // Resultat detaille du test, correction d'une application en echec,
    // authentification appliquee au test de connectivite.
    // ------------------------------------------------------------

    private String createWithAuth(String name, String url, Map<String, Object> auth) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        body.put("url", url);
        body.put("auth", auth);
        String response = mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("s3cret");
        return objectMapper.readTree(response).get("id").asText();
    }

    @Test
    void testAvailability_persistsMessageAndDate_visibleInGet() throws Exception {
        String id = createApplicationAndGetId("message-app");

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.message").value("Application joignable (HTTP 200)."));

        mockMvc.perform(get("/api/applications/" + id).with(AS_VIEWER))
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.statusMessage").value("Application joignable (HTTP 200)."))
                .andExpect(jsonPath("$.statusCheckedAt").value(notNullValue()));
    }

    @Test
    void testAvailability_401_setsFailedWithAuthenticationRefusedMessage() throws Exception {
        String id = createApplicationAndGetId("unauthorized-app");
        when(availabilityChecker.check(anyString(), any(Duration.class), any()))
                .thenReturn(new HttpAvailabilityResult(true, 401, 10L, null));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value(startsWith("Authentification refusée (401)")));
    }

    @Test
    void testAvailability_networkError_setsErrorWithReadableMessage() throws Exception {
        String id = createApplicationAndGetId("dns-app");
        when(availabilityChecker.check(anyString(), any(Duration.class), any()))
                .thenReturn(new HttpAvailabilityResult(false, null, 3L, "hôte introuvable (DNS)"));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value("Application injoignable : hôte introuvable (DNS)."));
    }

    @Test
    void update_resetsPreviousTestResult_untilRetested() throws Exception {
        String id = createApplicationAndGetId("fix-me");
        when(availabilityChecker.check(anyString(), any(Duration.class), any()))
                .thenReturn(new HttpAvailabilityResult(true, 404, 5L, null));
        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("FAILED"));

        mockMvc.perform(put("/api/applications/" + id).with(AS_SUPER_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(validRequestJson("fix-me", "http://example.com/fixed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(nullValue()))
                .andExpect(jsonPath("$.statusMessage").value(nullValue()));

        when(availabilityChecker.check(anyString(), any(Duration.class), any()))
                .thenReturn(new HttpAvailabilityResult(true, 200, 5L, null));
        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("CONNECTED"));
    }

    @Test
    void testAvailability_bearerToken_sendsAuthorizationHeader() throws Exception {
        String id = createWithAuth("bearer-app", "http://example.com/b", Map.of("type", "BEARER_TOKEN", "token", "s3cret", "verificationPath", "/me"));
        stubVerification(new HttpAvailabilityResult(true, 401, 5L, null), new HttpAvailabilityResult(true, 200, 5L, null));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("CONNECTED"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(availabilityChecker).check(anyString(), headers.capture(), any(Duration.class), any());
        assertThat(headers.getValue()).containsEntry("Authorization", "Bearer s3cret");
    }

    @Test
    void testAvailability_apiKeyInQuery_isAppendedToUrl() throws Exception {
        String id = createWithAuth("query-app", "http://example.com/q",
                Map.of("type", "API_KEY", "apiKeyName", "api_key", "apiKeyValue", "k-1", "apiKeyLocation", "QUERY",
                        "verificationPath", "/me?lang=fr"));
        stubVerification(new HttpAvailabilityResult(true, 401, 5L, null), new HttpAvailabilityResult(true, 200, 5L, null));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN));

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        Mockito.verify(availabilityChecker).check(url.capture(), anyMap(), any(Duration.class), any());
        assertThat(url.getValue()).isEqualTo("http://example.com/q/me?lang=fr&api_key=k-1");
    }

    @Test
    void testAvailability_loginEndpointUnreachable_setsErrorWithoutLeakingSecret() throws Exception {
        String id = createWithAuth("login-down", "http://localhost:1", Map.of("type", "LOGIN_TOKEN",
                "loginPath", "/api/auth/login", "loginBody", "{\"password\":\"s3cret\"}", "tokenJsonPath", "$.token"));

        String response = mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value(startsWith("Endpoint de connexion injoignable : login impossible")))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("s3cret");

        mockMvc.perform(get("/api/applications/" + id).with(AS_VIEWER))
                .andExpect(jsonPath("$.auth.type").value("LOGIN_TOKEN"))
                .andExpect(jsonPath("$.auth.loginBodyConfigured").value(true))
                .andExpect(jsonPath("$.auth.loginBody").doesNotExist());
    }

    @Test
    void create_invalidAuth_returns400WithSameMessageAsFrontend() throws Exception {
        Map<String, Object> body = Map.of("name", "bad", "url", "http://example.com", "auth",
                Map.of("type", "LOGIN_TOKEN", "loginPath", "/login", "loginBody", "not json", "tokenJsonPath", "$.token"));
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Le corps doit être un JSON valide."));
    }

    // ------------------------------------------------------------
    // Bearer / cle API / Basic : verification reelle des identifiants sur
    // l'endpoint de verification (GET sans auth -> 401/403, puis avec auth).
    // ------------------------------------------------------------

    private static final Map<String, Object> BEARER_WITH_CHECK =
            Map.of("type", "BEARER_TOKEN", "token", "s3cret", "verificationPath", "/api/account/balance");

    private void stubVerification(HttpAvailabilityResult anonymous, HttpAvailabilityResult authenticated) {
        when(availabilityChecker.check(anyString(), any(Duration.class), any())).thenReturn(anonymous);
        when(availabilityChecker.check(anyString(), anyMap(), any(Duration.class), any())).thenReturn(authenticated);
    }

    @Test
    void credentialCheck_validCredentials_connected_andBothRequestsTargetVerificationEndpoint() throws Exception {
        String id = createWithAuth("check-ok", "http://example.com", BEARER_WITH_CHECK);
        stubVerification(new HttpAvailabilityResult(true, 401, 3L, null), new HttpAvailabilityResult(true, 200, 4L, null));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.message").value("Identifiants valides (HTTP 200)."));

        ArgumentCaptor<String> anonymousUrl = ArgumentCaptor.forClass(String.class);
        Mockito.verify(availabilityChecker).check(anonymousUrl.capture(), any(Duration.class), any());
        assertThat(anonymousUrl.getValue()).isEqualTo("http://example.com/api/account/balance");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> authUrl = ArgumentCaptor.forClass(String.class);
        Mockito.verify(availabilityChecker).check(authUrl.capture(), headers.capture(), any(Duration.class), any());
        assertThat(authUrl.getValue()).isEqualTo("http://example.com/api/account/balance");
        assertThat(headers.getValue()).containsEntry("Authorization", "Bearer s3cret");
    }

    @Test
    void credentialCheck_wrongCredentials_failedNeverConnected() throws Exception {
        String id = createWithAuth("check-bad", "http://example.com", BEARER_WITH_CHECK);
        stubVerification(new HttpAvailabilityResult(true, 401, 3L, null), new HttpAvailabilityResult(true, 401, 4L, null));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("Jeton refusé (401)."));
    }

    @Test
    void credentialCheck_unprotectedEndpoint_failedWithExplicitMessage() throws Exception {
        String id = createWithAuth("check-public", "http://example.com", BEARER_WITH_CHECK);
        stubVerification(new HttpAvailabilityResult(true, 200, 3L, null), new HttpAvailabilityResult(true, 200, 4L, null));

        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("Endpoint non protégé : choisissez un endpoint qui exige l'authentification."));
        Mockito.verify(availabilityChecker, Mockito.never()).check(anyString(), anyMap(), any(Duration.class), any());
    }

    @Test
    void credentialCheck_otherCode_and_networkError() throws Exception {
        String id = createWithAuth("check-500", "http://example.com", BEARER_WITH_CHECK);
        stubVerification(new HttpAvailabilityResult(true, 403, 3L, null), new HttpAvailabilityResult(true, 500, 4L, null));
        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("L'endpoint de vérification a répondu avec une erreur (HTTP 500)."));

        stubVerification(new HttpAvailabilityResult(false, null, 3L, "délai de réponse dépassé (5 s)"), null);
        mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value("Endpoint de vérification injoignable : délai de réponse dépassé (5 s)."));
    }

    @Test
    void credentialCheck_missingVerificationPath_returns400() throws Exception {
        Map<String, Object> body = Map.of("name", "no-path", "url", "http://example.com",
                "auth", Map.of("type", "API_KEY", "apiKeyName", "X-API-Key", "apiKeyValue", "k"));
        mockMvc.perform(post("/api/applications").with(AS_SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Indiquez l'endpoint de vérification (ex : /api/account/balance)."));
    }

    // ------------------------------------------------------------
    // Message de refus propre a chaque type, et Connexion auto verifiee par
    // un POST reel sur l'endpoint de connexion (serveur HTTP local).
    // ------------------------------------------------------------

    @Test
    void credentialCheck_refusalMessage_dependsOnType() throws Exception {
        String apiKey = createWithAuth("refused-key", "http://example.com", Map.of("type", "API_KEY",
                "apiKeyName", "X-API-Key", "apiKeyValue", "s3cret", "verificationPath", "/api/auth/verify"));
        String basic = createWithAuth("refused-basic", "http://example.com", Map.of("type", "BASIC",
                "username", "agent001", "password", "s3cret", "verificationPath", "/api/auth/verify"));
        stubVerification(new HttpAvailabilityResult(true, 401, 3L, null), new HttpAvailabilityResult(true, 401, 3L, null));
        mockMvc.perform(post("/api/applications/" + apiKey + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("Clé API refusée (401)."));

        stubVerification(new HttpAvailabilityResult(true, 403, 3L, null), new HttpAvailabilityResult(true, 403, 3L, null));
        mockMvc.perform(post("/api/applications/" + basic + "/test").with(AS_SUPER_ADMIN))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value("Identifiants Basic refusés (403)."));
    }

    /** Serveur de connexion local : 200 + token pour le bon mot de passe,
     * 401 sinon ; "/no-token" repond 200 sans token. */
    private com.sun.net.httpserver.HttpServer startLoginServer() throws java.io.IOException {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean noToken = exchange.getRequestURI().getPath().equals("/no-token");
            boolean ok = "POST".equals(exchange.getRequestMethod()) && body.contains("\"good\"");
            byte[] out = (ok ? (noToken ? "{\"ok\":true}" : "{\"data\":{\"token\":\"tok\"}}") : "{\"error\":\"bad\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(ok ? 200 : 401, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void loginToken_connectivity_isTheRealLogin_neverAGetOnTheApplicationUrl() throws Exception {
        com.sun.net.httpserver.HttpServer server = startLoginServer();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            Map<String, Object> login = Map.of("type", "LOGIN_TOKEN", "loginPath", "/auth/login",
                    "loginBody", "{\"password\":\"good\"}", "tokenJsonPath", "$.data.token");

            String ok = createWithAuth("login-ok", base, login);
            mockMvc.perform(post("/api/applications/" + ok + "/test").with(AS_SUPER_ADMIN))
                    .andExpect(jsonPath("$.status").value("CONNECTED"))
                    .andExpect(jsonPath("$.message").value("Authentification réussie (token obtenu)."));

            String bad = createWithAuth("login-bad", base, Map.of("type", "LOGIN_TOKEN", "loginPath", "/auth/login",
                    "loginBody", "{\"password\":\"s3cret\"}", "tokenJsonPath", "$.data.token"));
            mockMvc.perform(post("/api/applications/" + bad + "/test").with(AS_SUPER_ADMIN))
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.message").value("Identifiants refusés (401)."));

            String noToken = createWithAuth("login-no-token", base, Map.of("type", "LOGIN_TOKEN", "loginPath", "/no-token",
                    "loginBody", "{\"password\":\"good\"}", "tokenJsonPath", "$.token"));
            mockMvc.perform(post("/api/applications/" + noToken + "/test").with(AS_SUPER_ADMIN))
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.message").value("Token introuvable à $.token : aucun champ « token » à la racine."));

            Mockito.verify(availabilityChecker, Mockito.never()).check(anyString(), any(Duration.class), any());
            Mockito.verify(availabilityChecker, Mockito.never()).check(anyString(), anyMap(), any(Duration.class), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loginToken_readFromResponseHeader_isStored_andAMissingHeaderIsExplainedByTester() throws Exception {
        com.sun.net.httpserver.HttpServer server = startLoginServer();
        try {
            String base = "http://localhost:" + server.getAddress().getPort();
            String id = createWithAuth("login-header", base, Map.of("type", "LOGIN_TOKEN", "loginPath", "/auth/login",
                    "loginBody", "{\"password\":\"good\"}", "tokenJsonPath", "X-Auth-Token", "tokenSource", "HEADER"));

            // Source enregistree dans la configuration (chiffree) et renvoyee par l'API.
            mockMvc.perform(get("/api/applications/" + id).with(AS_SUPER_ADMIN))
                    .andExpect(jsonPath("$.auth.tokenSource").value("HEADER"))
                    .andExpect(jsonPath("$.auth.tokenJsonPath").value("X-Auth-Token"));

            // Le serveur de connexion ne renvoie le jeton que dans le corps : en-tete absent.
            mockMvc.perform(post("/api/applications/" + id + "/test").with(AS_SUPER_ADMIN))
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.message").value("Token introuvable : en-tête « X-Auth-Token » absent de la réponse."));
        } finally {
            server.stop(0);
        }
    }
}
