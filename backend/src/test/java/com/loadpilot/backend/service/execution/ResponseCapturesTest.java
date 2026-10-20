package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.CaptureSource;
import com.loadpilot.backend.enums.HttpMethod;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Captures de valeurs dans une reponse : configuration, sources JSON / en-tete / HTML, valeur sensible. */
class ResponseCapturesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpHeaders HEADERS = HttpHeaders.of(Map.of(
            "X-Request-Id", List.of("req-77"), "Set-Cookie", List.of("SESSIONID=sess-42; Path=/")), (n, v) -> true);

    private static StepExecutionSpec step(String name, String path, CaptureSource source) {
        return new StepExecutionSpec(UUID.randomUUID(), "etape", HttpMethod.GET, "/x", null, null, null, null, null, null,
                null, name, path, null, null, source);
    }

    @Test
    void configured_onlyWithBothNameAndPath_andTheBodyIsNotNeededForAHeader() {
        assertThat(ResponseCaptures.configured(step("token", "$.token", null))).isTrue();
        assertThat(ResponseCaptures.configured(step("token", " ", null))).isFalse();
        assertThat(ResponseCaptures.configured(step(null, "$.token", null))).isFalse();
        assertThat(ResponseCaptures.needsBody(step("token", "$.token", null))).isTrue();
        assertThat(ResponseCaptures.needsBody(step("id", "X-Request-Id", CaptureSource.HEADER))).isFalse();
        assertThat(ResponseCaptures.needsBody(step("csrf", "input::attr(value)", CaptureSource.HTML))).isTrue();
        assertThat(ResponseCaptures.extract(step(null, null, null), "{}", HEADERS, MAPPER)).isEqualTo(ResponseCaptures.Captured.NONE);
    }

    @Test
    void extract_fromJsonHeaderCookieAndHtml() {
        assertThat(ResponseCaptures.extract(step("token", "$.data.token", null), "{\"data\":{\"token\":\"T-1\"}}", HEADERS, MAPPER).value())
                .isEqualTo("T-1");
        assertThat(ResponseCaptures.extract(step("id", "X-Request-Id", CaptureSource.HEADER), null, HEADERS, MAPPER).value())
                .isEqualTo("req-77");
        assertThat(ResponseCaptures.extract(step("sid", "Set-Cookie:SESSIONID", CaptureSource.HEADER), null, HEADERS, MAPPER).value())
                .isEqualTo("sess-42");
        String html = "<form><input type=\"hidden\" name=\"csrf_token\" value=\"csrf-9\"><input name=\"lang\" value=\"fr\"></form>";
        ResponseCaptures.Captured csrf = ResponseCaptures.extract(step("csrf", "input[name=csrf_token]::attr(value)", CaptureSource.HTML),
                html, HEADERS, MAPPER);
        assertThat(csrf.value()).isEqualTo("csrf-9");
        assertThat(csrf.sensitive()).as("element au nom de jeton : masque dans le detail").isTrue();
        ResponseCaptures.Captured lang = ResponseCaptures.extract(step("lang", "input[name=lang]::attr(value)", CaptureSource.HTML),
                html, HEADERS, MAPPER);
        assertThat(lang.value()).isEqualTo("fr");
        assertThat(lang.sensitive()).isFalse();
    }

    @Test
    void notFound_producesNoVariable_neverAnError() {
        VariableContext variables = new VariableContext(Map.of(), null);
        variables.startIteration(Map.of());
        StepExecutionSpec json = step("token", "$.missing", null);
        ResponseCaptures.Captured missing = ResponseCaptures.extract(json, "{\"a\":1}", HEADERS, MAPPER);
        assertThat(missing.value()).isNull();
        ResponseCaptures.store(json, missing, variables);
        assertThat(variables.get("token")).isNull();

        StepExecutionSpec found = step(" token ", "$.a", null);
        ResponseCaptures.store(found, ResponseCaptures.extract(found, "{\"a\":1}", HEADERS, MAPPER), variables);
        assertThat(variables.get("token")).as("nom de variable sans espaces").isEqualTo("1");
        assertThat(ResponseCaptures.extract(json, "pas du json", HEADERS, MAPPER).value()).isNull();
    }
}
