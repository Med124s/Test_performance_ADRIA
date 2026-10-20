package com.loadpilot.backend.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.request.ApplicationAuthRequest;
import com.loadpilot.backend.dto.response.ApplicationAuthResponse;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ApiKeyLocation;
import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.exception.LoadConfigurationException;
import org.junit.jupiter.api.Test;

class ApplicationAuthSupportTest {

    private final ApplicationAuthSupport support = new ApplicationAuthSupport(new ObjectMapper());

    private static ApplicationAuthRequest bearer(String token) {
        return new ApplicationAuthRequest(ApplicationAuthType.BEARER_TOKEN, token, null, null, null, null, null, null, null, null, null, null, "/api/secure");
    }

    private static ApplicationAuthRequest loginToken(String path, String body, String jsonPath) {
        return new ApplicationAuthRequest(ApplicationAuthType.LOGIN_TOKEN, null, null, null, null, null, null, path, body, jsonPath, null, null);
    }

    private static Application app() {
        return Application.builder().name("app").url("http://localhost:1").build();
    }

    @Test
    void create_withoutAuth_isNone() {
        Application app = app();
        support.apply(null, app, true);
        assertThat(app.getAuthType()).isEqualTo(ApplicationAuthType.NONE);
        assertThat(app.getAuthConfig()).isNull();
        assertThat(support.toSpec(app).isNone()).isTrue();
    }

    @Test
    void response_neverContainsSecrets_onlyConfiguredFlags() {
        Application app = app();
        support.apply(loginToken("/api/auth/login", "{\"password\":\"s3cret\"}", "$.token"), app, true);

        ApplicationAuthResponse response = support.toResponse(app);

        assertThat(response.type()).isEqualTo(ApplicationAuthType.LOGIN_TOKEN);
        assertThat(response.loginPath()).isEqualTo("/api/auth/login");
        assertThat(response.tokenJsonPath()).isEqualTo("$.token");
        assertThat(response.tokenHeaderName()).isEqualTo("Authorization");
        assertThat(response.loginBodyConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain("s3cret");
    }

    @Test
    void update_blankSecret_keepsStoredValue_forSameType() {
        Application app = app();
        support.apply(bearer("first-token"), app, true);

        support.apply(bearer(""), app, false);

        assertThat(support.toSpec(app).config().token()).isEqualTo("first-token");
    }

    @Test
    void update_nullAuth_keepsExistingConfiguration() {
        Application app = app();
        support.apply(bearer("tok"), app, true);

        support.apply(null, app, false);

        assertThat(app.getAuthType()).isEqualTo(ApplicationAuthType.BEARER_TOKEN);
    }

    @Test
    void update_typeChange_dropsPreviousSecrets() {
        Application app = app();
        support.apply(new ApplicationAuthRequest(ApplicationAuthType.BASIC, null, null, null, null, "u", "p", null, null, null, null, null, "/api/secure"), app, true);

        assertThatThrownBy(() -> support.apply(bearer(null), app, false))
                .isInstanceOf(LoadConfigurationException.class)
                .hasMessageContaining("jeton");
    }

    @Test
    void switchingToNone_clearsConfiguration() {
        Application app = app();
        support.apply(bearer("tok"), app, true);

        support.apply(new ApplicationAuthRequest(ApplicationAuthType.NONE, null, null, null, null, null, null, null, null, null, null, null), app, false);

        assertThat(app.getAuthType()).isEqualTo(ApplicationAuthType.NONE);
        assertThat(app.getAuthConfig()).isNull();
    }

    @Test
    void apiKey_defaultsToHeader_andRejectsInvalidHeaderName() {
        Application app = app();
        support.apply(new ApplicationAuthRequest(ApplicationAuthType.API_KEY, null, "X-API-Key", "k", null, null, null, null, null, null, null, null, "/api/secure"), app, true);
        assertThat(support.toSpec(app).config().apiKeyLocation()).isEqualTo(ApiKeyLocation.HEADER);

        assertThatThrownBy(() -> support.apply(new ApplicationAuthRequest(ApplicationAuthType.API_KEY, null, "X API Key", "k", ApiKeyLocation.HEADER, null, null, null, null, null, null, null), app(), true))
                .isInstanceOf(LoadConfigurationException.class)
                .hasMessageContaining("Nom d'en-tête invalide");
    }

    @Test
    void loginToken_requiresPathBodyAndJsonPath() {
        assertThatThrownBy(() -> support.apply(loginToken(null, "{}", "$.token"), app(), true))
                .isInstanceOf(LoadConfigurationException.class).hasMessageContaining("endpoint de connexion");
        assertThatThrownBy(() -> support.apply(loginToken("/login", null, "$.token"), app(), true))
                .isInstanceOf(LoadConfigurationException.class).hasMessageContaining("identifiants envoyés");
        assertThatThrownBy(() -> support.apply(loginToken("/login", "{}", " "), app(), true))
                .isInstanceOf(LoadConfigurationException.class).hasMessageContaining("jeton");
    }

    @Test
    void loginToken_rejectsInvalidJsonBody_andUnsupportedTokenPath() {
        assertThatThrownBy(() -> support.apply(loginToken("/login", "{username: x}", "$.token"), app(), true))
                .isInstanceOf(LoadConfigurationException.class).hasMessage("Le corps doit être un JSON valide.");
        assertThatThrownBy(() -> support.apply(loginToken("/login", "{}", "$.items[-1].token"), app(), true))
                .isInstanceOf(LoadConfigurationException.class).hasMessageContaining("Chemin invalide");
        assertThatThrownBy(() -> support.apply(loginToken("/login", "{}", "$.items[a].token"), app(), true))
                .isInstanceOf(LoadConfigurationException.class).hasMessageContaining("Chemin invalide");
    }

    private static ApplicationAuthRequest loginTokenInHeader(String header) {
        return new ApplicationAuthRequest(ApplicationAuthType.LOGIN_TOKEN, null, null, null, null, null, null, "/login", "{}",
                header, null, null, null, com.loadpilot.backend.enums.CaptureSource.HEADER);
    }

    @Test
    void loginToken_tokenInResponseHeader_isValidatedAsAHeaderName_notAJsonPath() {
        Application app = app();
        support.apply(loginTokenInHeader("X-Auth-Token"), app, true);
        assertThat(support.toSpec(app).config().tokenFromHeader()).isTrue();
        assertThat(support.toResponse(app).tokenSource()).isEqualTo(com.loadpilot.backend.enums.CaptureSource.HEADER);
        support.apply(loginTokenInHeader("Set-Cookie:ACCESS"), app, true);
        assertThat(support.toSpec(app).config().tokenJsonPath()).isEqualTo("Set-Cookie:ACCESS");

        assertThatThrownBy(() -> support.apply(loginTokenInHeader("X Auth Token"), app(), true))
                .isInstanceOf(LoadConfigurationException.class)
                .hasMessage("Jeton : nom d'en-tête invalide (pas d'espace ni de caractère spécial, ex. X-CSRF-Token).");
        assertThatThrownBy(() -> support.apply(loginTokenInHeader(" "), app(), true))
                .isInstanceOf(LoadConfigurationException.class)
                .hasMessage("Indiquez l'en-tête qui contient le jeton (ex : X-Auth-Token).");
    }

    @Test
    void loginToken_fromHtmlPage_isRejectedWithAnExplanation() {
        ApplicationAuthRequest request = new ApplicationAuthRequest(ApplicationAuthType.LOGIN_TOKEN, null, null, null, null, null, null,
                "/login", "{}", "input[name=token]::attr(value)", null, null, null, com.loadpilot.backend.enums.CaptureSource.HTML);
        assertThatThrownBy(() -> support.apply(request, app(), true))
                .isInstanceOf(LoadConfigurationException.class)
                .hasMessageContaining("faites de la connexion une étape du scénario");
    }

    @Test
    void loginToken_withoutSource_staysJson() {
        Application app = app();
        support.apply(loginToken("/login", "{}", "$.data.token"), app, true);
        assertThat(support.toSpec(app).config().tokenFromHeader()).isFalse();
        assertThat(support.toResponse(app).tokenSource()).isNull();
    }

    @Test
    void loginToken_acceptsArrayIndexesInTokenPath() {
        Application app = app();
        support.apply(loginToken("/login", "{}", "$.data.sessions[0].token"), app, true);
        assertThat(support.toSpec(app).config().tokenJsonPath()).isEqualTo("$.data.sessions[0].token");
        support.apply(loginToken("/login", "{}", "$[0][1].token"), app, true);
        assertThat(support.toSpec(app).config().tokenJsonPath()).isEqualTo("$[0][1].token");
    }

    @Test
    void loginToken_acceptsNestedPathsAndAbsoluteLoginUrl() {
        Application app = app();
        support.apply(loginToken("https://auth.example.com/oauth/token", "{\"a\":1}", "$.data.accessToken"), app, true);
        assertThat(support.toSpec(app).config().tokenJsonPath()).isEqualTo("$.data.accessToken");
        assertThat(support.toSpec(app).config().loginPath()).isEqualTo("https://auth.example.com/oauth/token");
    }

    @Test
    void loginToken_blankBodyOnUpdate_keepsStoredBody_evenIfNotRetyped() {
        Application app = app();
        support.apply(loginToken("/login", "{\"password\":\"s3cret\"}", "$.token"), app, true);

        support.apply(loginToken("/login2", "", "$.access_token"), app, false);

        assertThat(support.toSpec(app).config().loginBody()).isEqualTo("{\"password\":\"s3cret\"}");
        assertThat(support.toSpec(app).config().loginPath()).isEqualTo("/login2");
    }

    @Test
    void headerValues_withLineBreak_areRejected() {
        assertThatThrownBy(() -> support.apply(bearer("abc\r\nX-Injected: 1"), app(), true))
                .isInstanceOf(LoadConfigurationException.class).hasMessageContaining("retour à la ligne");
    }

    @Test
    void apiKeyInQuery_rejectsNameWithSeparators() {
        assertThatThrownBy(() -> support.apply(new ApplicationAuthRequest(ApplicationAuthType.API_KEY, null, "api key", "k", ApiKeyLocation.QUERY, null, null, null, null, null, null, null), app(), true))
                .isInstanceOf(LoadConfigurationException.class).hasMessageContaining("Nom de paramètre invalide");
    }

    @Test
    void update_newSecret_replacesStoredSecret() {
        Application app = app();
        support.apply(bearer("old"), app, true);

        support.apply(bearer("new"), app, false);

        assertThat(support.toSpec(app).config().token()).isEqualTo("new");
    }

    @Test
    void credentialTypes_requireVerificationPath_withoutSpaces() {
        for (ApplicationAuthRequest request : new ApplicationAuthRequest[] {
                new ApplicationAuthRequest(ApplicationAuthType.BEARER_TOKEN, "t", null, null, null, null, null, null, null, null, null, null, null),
                new ApplicationAuthRequest(ApplicationAuthType.API_KEY, null, "X-API-Key", "k", ApiKeyLocation.HEADER, null, null, null, null, null, null, null, " "),
                new ApplicationAuthRequest(ApplicationAuthType.BASIC, null, null, null, null, "u", "p", null, null, null, null, null, null)}) {
            assertThatThrownBy(() -> support.apply(request, app(), true))
                    .isInstanceOf(LoadConfigurationException.class)
                    .hasMessage("Indiquez l'endpoint de vérification (ex : /api/account/balance).");
        }
        assertThatThrownBy(() -> support.apply(new ApplicationAuthRequest(ApplicationAuthType.BEARER_TOKEN, "t", null, null, null, null, null, null, null, null, null, null, "/api/account balance"), app(), true))
                .isInstanceOf(LoadConfigurationException.class)
                .hasMessage("L'endpoint de vérification ne doit pas contenir d'espace.");
    }

    @Test
    void verificationPath_isStoredAndReturned_relativeOrAbsolute() {
        Application app = app();
        support.apply(new ApplicationAuthRequest(ApplicationAuthType.BASIC, null, null, null, null, "u", "p", null, null, null, null, null, "https://api.example.com/me"), app, true);

        assertThat(support.toSpec(app).config().verificationPath()).isEqualTo("https://api.example.com/me");
        assertThat(support.toResponse(app).verificationPath()).isEqualTo("https://api.example.com/me");
        assertThat(support.toResponse(app).toString()).doesNotContain("=p,");
    }

    @Test
    void noneAndLoginToken_doNotRequireVerificationPath() {
        Application app = app();
        support.apply(loginToken("/login", "{}", "$.token"), app, true);
        assertThat(support.toSpec(app).config().verificationPath()).isNull();
    }
}
