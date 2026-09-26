package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class VariableResolverTest {

    @Test
    void nullTemplate_returnsNull() {
        assertThat(VariableResolver.substitute(null, Map.of("a", "b"))).isNull();
    }

    @Test
    void emptyVariables_returnsTemplateUnchanged() {
        assertThat(VariableResolver.substitute("/users/${id}", Map.of())).isEqualTo("/users/${id}");
    }

    @Test
    void singleVariable_isSubstituted() {
        assertThat(VariableResolver.substitute("/users/${id}", Map.of("id", "42"))).isEqualTo("/users/42");
    }

    @Test
    void multipleOccurrencesOfSameVariable_allSubstituted() {
        assertThat(VariableResolver.substitute("${a}-${a}", Map.of("a", "x"))).isEqualTo("x-x");
    }

    @Test
    void unknownVariable_leftUntouched_neverInvented() {
        assertThat(VariableResolver.substitute("${unknown}", Map.of("a", "b"))).isEqualTo("${unknown}");
    }

    @Test
    void multipleDifferentVariables_allSubstituted() {
        String result = VariableResolver.substitute("${a}/${b}", Map.of("a", "1", "b", "2"));
        assertThat(result).isEqualTo("1/2");
    }

    @Test
    void nullValueForKey_substitutedAsEmptyString_neverThrows() {
        java.util.HashMap<String, String> vars = new java.util.HashMap<>();
        vars.put("a", null);
        assertThat(VariableResolver.substitute("x${a}y", vars)).isEqualTo("xy");
    }
}
