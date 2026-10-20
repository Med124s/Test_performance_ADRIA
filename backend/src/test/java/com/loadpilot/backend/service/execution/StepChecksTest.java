package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.JsonAssertionOperator;
import org.junit.jupiter.api.Test;

/** Controles optionnels d'une etape : temps de reponse maximal et assertion JSON simple. */
class StepChecksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BODY = "{\"status\":\"OK\",\"count\":3,\"active\":true,\"message\":\"Virement effectué\","
            + "\"items\":[\"a\",\"b\"],\"data\":{\"id\":7,\"owner\":null}}";

    private static String json(String path, JsonAssertionOperator op, String expected) {
        return StepChecks.jsonAssertionProblem(MAPPER, BODY, path, op, expected);
    }

    @Test
    void responseTime_okUpToTheMaximum_failsAbove_andIsIgnoredWithoutMaximum() {
        assertThat(StepChecks.responseTimeProblem(500, 500)).isNull();
        assertThat(StepChecks.responseTimeProblem(500, 812))
                .isEqualTo("Temps de réponse trop long : 812 ms (maximum autorisé 500 ms)");
        assertThat(StepChecks.responseTimeProblem(null, 99_999)).isNull();
        assertThat(StepChecks.responseTimeProblem(0, 99_999)).isNull();
    }

    @Test
    void equals_comparesTextNumbersAndBooleans() {
        assertThat(json("$.status", JsonAssertionOperator.EQUALS, "OK")).isNull();
        assertThat(json("count", JsonAssertionOperator.EQUALS, "3")).isNull();
        assertThat(json("$.active", null, "true")).as("comparaison EQUALS par defaut").isNull();
        assertThat(json("$.data.id", JsonAssertionOperator.EQUALS, "7")).isNull();
        assertThat(json("$.status", JsonAssertionOperator.EQUALS, "KO"))
                .isEqualTo("Assertion JSON : $.status vaut « OK », attendu « KO »");
        assertThat(json("$.status", JsonAssertionOperator.EQUALS, "ok")).as("sensible a la casse").isNotNull();
    }

    @Test
    void equals_onAnArrayOrObject_comparesTheJson() {
        assertThat(json("$.items", JsonAssertionOperator.EQUALS, "[ \"a\", \"b\" ]")).isNull();
        assertThat(json("$.data", JsonAssertionOperator.EQUALS, "{\"owner\":null,\"id\":7}")).isNull();
        assertThat(json("$.items", JsonAssertionOperator.EQUALS, "[\"b\",\"a\"]")).isNotNull();
    }

    @Test
    void contains_searchesInTheFieldValue_includingArrays() {
        assertThat(json("$.message", JsonAssertionOperator.CONTAINS, "effectué")).isNull();
        assertThat(json("$.items", JsonAssertionOperator.CONTAINS, "\"b\"")).isNull();
        assertThat(json("$.message", JsonAssertionOperator.CONTAINS, "refusé"))
                .isEqualTo("Assertion JSON : $.message ne contient pas « refusé » (valeur « Virement effectué »)");
    }

    @Test
    void missingFieldOrNonJsonResponse_failsWithTheReason() {
        assertThat(json("$.missing", JsonAssertionOperator.EQUALS, "x"))
                .isEqualTo("Assertion JSON : champ $.missing introuvable (aucun champ « missing » à la racine)");
        assertThat(json("$.data.owner", JsonAssertionOperator.EQUALS, "x")).contains("valeur nulle");
        assertThat(StepChecks.jsonAssertionProblem(MAPPER, "<html>", "$.status", JsonAssertionOperator.EQUALS, "OK"))
                .contains("réponse non JSON");
        assertThat(StepChecks.jsonAssertionProblem(MAPPER, null, "$.status", JsonAssertionOperator.EQUALS, "OK"))
                .contains("réponse vide");
    }

    @Test
    void withoutPath_noAssertion() {
        assertThat(StepChecks.jsonAssertionProblem(MAPPER, "pas du json", null, null, null)).isNull();
        assertThat(StepChecks.jsonAssertionProblem(MAPPER, "pas du json", "  ", JsonAssertionOperator.EQUALS, "x")).isNull();
    }

    @Test
    void configuration_requiresAValidPath_andATextForContains() {
        assertThat(StepChecks.configurationProblem(null, null, null)).isNull();
        assertThat(StepChecks.configurationProblem("$.status", JsonAssertionOperator.EQUALS, "")).as("egal a vide autorise").isNull();
        assertThat(StepChecks.configurationProblem("data.items[0].id", null, "1")).isNull();
        assertThat(StepChecks.configurationProblem(null, JsonAssertionOperator.EQUALS, "OK"))
                .isEqualTo("Assertion JSON : indiquez le champ à vérifier (ex. $.status).");
        assertThat(StepChecks.configurationProblem("items[x]", JsonAssertionOperator.EQUALS, "1"))
                .isEqualTo("Assertion JSON : champ invalide (ex. $.status ou data.items[0].id).");
        assertThat(StepChecks.configurationProblem("$.message", JsonAssertionOperator.CONTAINS, ""))
                .isEqualTo("Assertion JSON : indiquez le texte que le champ doit contenir.");
    }

    @Test
    void capture_isUnchanged_containersStillCapturedAsEmptyText() {
        assertThat(JsonPathExtractor.extract(MAPPER, BODY, "$.items")).isEmpty();
        assertThat(JsonPathExtractor.extractForAssertion(MAPPER, BODY, "$.items")).isEqualTo("[\"a\",\"b\"]");
    }
}
