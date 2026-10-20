package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseHeaderExtractorTest {

    /** En-tetes tels que les construit java.net.http (recherche insensible a la casse). */
    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (name, value) -> true);
    }

    private static final HttpHeaders RESPONSE = headers(Map.of(
            "X-CSRF-Token", List.of("abc123"),
            "X-Multi", List.of("first", "second"),
            "X-Blank", List.of("  "),
            "Set-Cookie", List.of("theme=dark; Path=/", "SESSIONID=sess-42; Path=/; HttpOnly", "quoted=\"v 1\"; Secure", "empty=; Path=/")));

    @Test
    void headerValue_extracted_withoutExplanation() {
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "X-CSRF-Token")).isEqualTo("abc123");
        assertThat(ResponseHeaderExtractor.explain(RESPONSE, "X-CSRF-Token")).isNull();
    }

    @Test
    void headerName_isCaseInsensitive() {
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "x-csrf-token")).isEqualTo("abc123");
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "X-CSRF-TOKEN")).isEqualTo("abc123");
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "  X-Csrf-Token  ")).isEqualTo("abc123");
    }

    @Test
    void absentHeader_returnsNull_withClearExplanation() {
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "X-Request-Id")).isNull();
        assertThat(ResponseHeaderExtractor.explain(RESPONSE, "X-Request-Id")).isEqualTo("en-tête « X-Request-Id » absent de la réponse");
    }

    @Test
    void severalValues_theFirstOneIsKept() {
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "X-Multi")).isEqualTo("first");
    }

    @Test
    void blankHeader_isExplained() {
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "X-Blank")).isNull();
        assertThat(ResponseHeaderExtractor.explain(RESPONSE, "X-Blank")).isEqualTo("en-tête « X-Blank » vide");
    }

    @Test
    void namedCookie_isFoundAmongSeveralSetCookieHeaders() {
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "Set-Cookie:SESSIONID")).isEqualTo("sess-42");
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "set-cookie:theme")).isEqualTo("dark");
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "Set-Cookie:quoted")).isEqualTo("v 1");
    }

    @Test
    void setCookieWithoutCookieName_returnsTheFirstRawValue() {
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "Set-Cookie")).isEqualTo("theme=dark; Path=/");
    }

    @Test
    void cookieName_isCaseSensitive_andMissingCookieIsExplained() {
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "Set-Cookie:sessionid")).isNull();
        assertThat(ResponseHeaderExtractor.explain(RESPONSE, "Set-Cookie:JSESSIONID"))
                .isEqualTo("aucun cookie « JSESSIONID » dans les en-têtes Set-Cookie de la réponse");
        assertThat(ResponseHeaderExtractor.explain(RESPONSE, "Set-Cookie:empty")).isEqualTo("cookie « empty » vide");
    }

    @Test
    void cookieWithoutAnySetCookieHeader_isExplainedAsAbsentHeader() {
        assertThat(ResponseHeaderExtractor.explain(headers(Map.of()), "Set-Cookie:SESSIONID"))
                .isEqualTo("en-tête « Set-Cookie » absent de la réponse");
    }

    @Test
    void cookieNameOnAnotherHeader_isInvalid_neverThrows() {
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, "X-CSRF-Token:abc")).isNull();
        assertThat(ResponseHeaderExtractor.explain(RESPONSE, "X-CSRF-Token:abc")).startsWith("en-tête invalide");
        assertThat(ResponseHeaderExtractor.explain(RESPONSE, "Set-Cookie:")).startsWith("en-tête invalide");
    }

    @Test
    void nullOrBlankInputs_returnNull_withoutExplanation() {
        assertThat(ResponseHeaderExtractor.extract(null, "X-CSRF-Token")).isNull();
        assertThat(ResponseHeaderExtractor.extract(RESPONSE, " ")).isNull();
        assertThat(ResponseHeaderExtractor.explain(RESPONSE, null)).isNull();
    }
}
