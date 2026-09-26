package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonPathExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void nullJson_returnsNull() {
        assertThat(JsonPathExtractor.extract(objectMapper, null, "token")).isNull();
    }

    @Test
    void nullPath_returnsNull() {
        assertThat(JsonPathExtractor.extract(objectMapper, "{\"token\":\"abc\"}", null)).isNull();
    }

    @Test
    void topLevelField_plainPath_extracted() {
        assertThat(JsonPathExtractor.extract(objectMapper, "{\"token\":\"abc123\"}", "token")).isEqualTo("abc123");
    }

    @Test
    void topLevelField_dollarDotPrefix_extracted() {
        assertThat(JsonPathExtractor.extract(objectMapper, "{\"token\":\"abc123\"}", "$.token")).isEqualTo("abc123");
    }

    @Test
    void nestedField_dotPath_extracted() {
        assertThat(JsonPathExtractor.extract(objectMapper, "{\"data\":{\"token\":\"xyz\"}}", "data.token")).isEqualTo("xyz");
    }

    @Test
    void nestedField_dollarDotPrefix_extracted() {
        assertThat(JsonPathExtractor.extract(objectMapper, "{\"data\":{\"token\":\"xyz\"}}", "$.data.token")).isEqualTo("xyz");
    }

    @Test
    void missingField_returnsNull_neverThrows() {
        assertThat(JsonPathExtractor.extract(objectMapper, "{\"other\":\"value\"}", "token")).isNull();
    }

    @Test
    void missingNestedParent_returnsNull_neverThrows() {
        assertThat(JsonPathExtractor.extract(objectMapper, "{\"other\":\"value\"}", "data.token")).isNull();
    }

    @Test
    void nullValueField_returnsNull() {
        assertThat(JsonPathExtractor.extract(objectMapper, "{\"token\":null}", "token")).isNull();
    }

    @Test
    void invalidJson_returnsNull_neverThrows() {
        assertThat(JsonPathExtractor.extract(objectMapper, "not-json-at-all", "token")).isNull();
    }

    @Test
    void numericField_extractedAsText() {
        assertThat(JsonPathExtractor.extract(objectMapper, "{\"count\":42}", "count")).isEqualTo("42");
    }
}
