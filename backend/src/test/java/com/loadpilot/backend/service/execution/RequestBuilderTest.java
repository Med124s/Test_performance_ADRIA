package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.CsvDistribution;
import com.loadpilot.backend.enums.HttpMethod;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Construction de la requete reelle (variables de chemin, query, encodage) et priorite des sources. */
class RequestBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "http://localhost:8888";

    private static StepExecutionSpec step(String url, String headers, String body, String pathParams, String queryParams) {
        return new StepExecutionSpec(UUID.randomUUID(), "s", HttpMethod.GET, url, headers, body, null, null, null, null,
                null, null, null, pathParams, queryParams);
    }

    private static RequestBuilder.BuiltRequest build(StepExecutionSpec step, Map<String, String> vars) {
        return RequestBuilder.build(step, BASE, vars::get, MAPPER);
    }

    @Test
    void pathVariables_colonAndBraces_defaultToSameNameVariable_andAreEncoded() {
        var built = build(step("/api/transfers/:transferId/notes/{note}", null, null, "{\"note\":\"${label}\"}", null),
                Map.of("transferId", "TRF 01/A", "label", "café"));
        assertThat(built.url()).isEqualTo(BASE + "/api/transfers/TRF%2001%2FA/notes/caf%C3%A9");
        assertThat(built.missing()).isEmpty();
    }

    @Test
    void injectedValuesAreEncoded_inPathAndQuery_butTypedTextIsKept() {
        var built = build(step("/search/${term}?q=${term}&lang=fr", null, null, null,
                "[{\"key\":\"page\",\"value\":\"${page}\",\"enabled\":true},{\"key\":\"debug\",\"value\":\"1\",\"enabled\":false},"
                        + "{\"key\":\"sort by\",\"value\":\"date desc\",\"enabled\":true}]"),
                Map.of("term", "a&b=c d", "page", "2"));
        assertThat(built.url()).isEqualTo(BASE + "/search/a%26b%3Dc%20d?q=a%26b%3Dc%20d&lang=fr&page=2&sort%20by=date%20desc");
    }

    @Test
    void absoluteUrlPort_isNeverMistakenForAPathVariable() {
        var built = build(step("http://localhost:8888/api/:id", null, null, null, null), Map.of("id", "7"));
        assertThat(built.url()).isEqualTo("http://localhost:8888/api/7");
    }

    @Test
    void headersAndBody_areSubstitutedRaw_andMissingVariablesAreCollected() {
        var built = build(step("/x/${transferId}", "{\"Authorization\":\"Bearer ${token}\"}", "{\"amount\": ${amount}}", null, null),
                Map.of("amount", "12.5"));
        assertThat(built.body()).isEqualTo("{\"amount\": 12.5}");
        assertThat(built.missing()).containsExactlyInAnyOrder("transferId", "token");
        assertThat(built.url()).contains("${transferId}");
    }

    @Test
    void injectedValue_isNeverReinterpreted_asATemplate() {
        var built = build(step("/x", null, "${a}", null, null), Map.of("a", "${b}", "b", "SECRET"));
        assertThat(built.body()).isEqualTo("${b}");
        assertThat(built.missing()).isEmpty();
    }

    @Test
    void missingMessage_pointsToTheCapturingStep() {
        assertThat(RequestBuilder.missingMessage(Set.of("transferId"), 5, Map.of("transferId", 3)))
                .isEqualTo("Variable ${transferId} introuvable : vérifiez la capture de l'étape 3 ou le CSV.");
        assertThat(RequestBuilder.missingMessage(Set.of("transferId"), 2, Map.of("transferId", 3)))
                .contains("capturée qu'à l'étape 3, après cette étape");
        assertThat(RequestBuilder.missingMessage(Set.of("x"), 2, Map.of()))
                .contains("ni capturée par une étape, ni colonne du CSV, ni variable du scénario");
    }

    @Test
    void variableContext_priority_captureThenCsvThenScenarioThenAuthToken_andCapturesResetPerIteration() {
        VariableContext ctx = new VariableContext(Map.of("currency", "MAD", "amount", "1", "authToken", "scenario-wins"), "TOKEN");
        ctx.startIteration(Map.of("amount", "150"));
        assertThat(ctx.get("amount")).isEqualTo("150");
        assertThat(ctx.get("currency")).isEqualTo("MAD");
        assertThat(ctx.get("authToken")).isEqualTo("scenario-wins");
        ctx.capture("amount", "999");
        assertThat(ctx.get("amount")).isEqualTo("999");
        assertThat(ctx.sourceOf("amount")).isEqualTo(VariableContext.Source.CAPTURE);

        ctx.startIteration(Map.of("amount", "200"));
        assertThat(ctx.get("amount")).isEqualTo("200");
        assertThat(ctx.sourceOf("amount")).isEqualTo(VariableContext.Source.CSV);
        assertThat(new VariableContext(Map.of(), "TOKEN").get("authToken")).isEqualTo("TOKEN");
    }

    @Test
    void perIterationDistribution_consumesRowsInOrder_cyclically() {
        List<Map<String, String>> rows = List.of(Map.of("n", "1"), Map.of("n", "2"), Map.of("n", "3"));
        VariableSources perIteration = new VariableSources(rows, CsvDistribution.PER_ITERATION, Map.of(), List.of());
        assertThat(List.of(perIteration.rowFor(0), perIteration.rowFor(1), perIteration.rowFor(0), perIteration.rowFor(4)))
                .extracting(r -> r.get("n")).containsExactly("1", "2", "3", "1");
        VariableSources perVu = new VariableSources(rows, CsvDistribution.PER_VIRTUAL_USER, Map.of(), List.of());
        assertThat(List.of(perVu.rowFor(0), perVu.rowFor(0), perVu.rowFor(4))).extracting(r -> r.get("n")).containsExactly("1", "1", "2");
    }
}
