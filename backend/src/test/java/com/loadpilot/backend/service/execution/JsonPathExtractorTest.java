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

    // ---------------------------------------------------------------- index de tableau

    private static final String ACCOUNTS = "{\"data\":{\"accounts\":[{\"id\":\"ACC001\",\"balance\":1500},{\"id\":\"ACC002\",\"balance\":30}]}}";

    @Test
    void foundValue_hasNoExplanation() {
        assertThat(JsonPathExtractor.explain(objectMapper, ACCOUNTS, "$.data.accounts[1].id")).isNull();
    }

    @Test
    void simpleArray_indexAtTopLevel() {
        String json = "{\"items\":[\"a\",\"b\",\"c\"]}";
        assertThat(JsonPathExtractor.extract(objectMapper, json, "items[0]")).isEqualTo("a");
        assertThat(JsonPathExtractor.extract(objectMapper, json, "$.items[2]")).isEqualTo("c");
    }

    @Test
    void rootArray_dollarIndexPrefix() {
        String json = "[{\"id\":\"first\"},{\"id\":\"second\"}]";
        assertThat(JsonPathExtractor.extract(objectMapper, json, "$[1].id")).isEqualTo("second");
        assertThat(JsonPathExtractor.extract(objectMapper, json, "[0].id")).isEqualTo("first");
    }

    @Test
    void arrayNestedInObject() {
        assertThat(JsonPathExtractor.extract(objectMapper, ACCOUNTS, "data.accounts[0].id")).isEqualTo("ACC001");
    }

    @Test
    void objectNestedInArray() {
        String json = "{\"items\":[{\"name\":\"x\"},{\"name\":\"y\"},{\"name\":\"z\"}]}";
        assertThat(JsonPathExtractor.extract(objectMapper, json, "items[2].name")).isEqualTo("z");
    }

    @Test
    void mixedLevels_objectArrayObjectArrayObject() {
        String json = "{\"data\":{\"customers\":[{\"accounts\":[{\"iban\":\"MA01\"},{\"iban\":\"MA02\"}]}]}}";
        assertThat(JsonPathExtractor.extract(objectMapper, json, "$.data.customers[0].accounts[1].iban")).isEqualTo("MA02");
    }

    @Test
    void arrayOfArrays_consecutiveIndexes() {
        String json = "{\"data\":[[{\"value\":1},{\"value\":2}],[{\"value\":3}]]}";
        assertThat(JsonPathExtractor.extract(objectMapper, json, "data[0][1].value")).isEqualTo("2");
        assertThat(JsonPathExtractor.extract(objectMapper, json, "data[1][0].value")).isEqualTo("3");
    }

    @Test
    void indexOutOfBounds_returnsNull_withExplanation() {
        assertThat(JsonPathExtractor.extract(objectMapper, ACCOUNTS, "data.accounts[2].id")).isNull();
        assertThat(JsonPathExtractor.explain(objectMapper, ACCOUNTS, "data.accounts[2].id"))
                .isEqualTo("index 2 hors limites pour data.accounts (tableau de 2 éléments)");
    }

    @Test
    void indexOnEmptyArray_explained() {
        assertThat(JsonPathExtractor.explain(objectMapper, "{\"items\":[]}", "items[0]"))
                .isEqualTo("index 0 hors limites pour items (tableau vide)");
    }

    @Test
    void indexOnObject_returnsNull_withExplanation() {
        assertThat(JsonPathExtractor.extract(objectMapper, ACCOUNTS, "data[0]")).isNull();
        assertThat(JsonPathExtractor.explain(objectMapper, ACCOUNTS, "data[0]")).isEqualTo("data n'est pas un tableau (objet)");
    }

    @Test
    void fieldOnArray_returnsNull_withExplanation() {
        assertThat(JsonPathExtractor.extract(objectMapper, ACCOUNTS, "data.accounts.id")).isNull();
        assertThat(JsonPathExtractor.explain(objectMapper, ACCOUNTS, "data.accounts.id"))
                .isEqualTo("data.accounts est un tableau : indiquez un index (ex. data.accounts[0])");
    }

    @Test
    void fieldOnRootArray_explained() {
        assertThat(JsonPathExtractor.explain(objectMapper, "[{\"id\":1}]", "id"))
                .isEqualTo("la racine est un tableau : indiquez un index (ex. $[0])");
    }

    @Test
    void fieldOnSimpleValue_explained() {
        assertThat(JsonPathExtractor.explain(objectMapper, ACCOUNTS, "data.accounts[0].id.value"))
                .isEqualTo("data.accounts[0].id n'est pas un objet (valeur simple)");
    }

    @Test
    void missingFieldInsideArrayElement_explained() {
        assertThat(JsonPathExtractor.extract(objectMapper, ACCOUNTS, "data.accounts[0].iban")).isNull();
        assertThat(JsonPathExtractor.explain(objectMapper, ACCOUNTS, "data.accounts[0].iban"))
                .isEqualTo("aucun champ « iban » dans data.accounts[0]");
    }

    @Test
    void missingFieldAtRoot_andNullValue_explained() {
        assertThat(JsonPathExtractor.explain(objectMapper, "{\"other\":1}", "$.token")).isEqualTo("aucun champ « token » à la racine");
        assertThat(JsonPathExtractor.explain(objectMapper, "{\"items\":[null]}", "items[0]")).isEqualTo("valeur nulle à items[0]");
    }

    @Test
    void invalidIndexSyntax_returnsNull_neverThrows() {
        for (String path : new String[] {"items[-1]", "items[a]", "items[0", "items]0[", "items[]", "items[1.5]", "it[0]ems", "items[99999999999]"}) {
            assertThat(JsonPathExtractor.extract(objectMapper, "{\"items\":[1,2]}", path)).as(path).isNull();
            assertThat(JsonPathExtractor.explain(objectMapper, "{\"items\":[1,2]}", path)).as(path).startsWith("chemin invalide");
        }
    }

    @Test
    void invalidJson_isExplained() {
        assertThat(JsonPathExtractor.explain(objectMapper, "not-json-at-all", "items[0]")).isEqualTo("réponse non JSON");
    }
}
