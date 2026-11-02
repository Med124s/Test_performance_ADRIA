package com.loadpilot.backend.service.execution;

import java.util.Map;

/** Remplacement des variables {@code ${nom}} dans un texte. */
public final class VariableResolver {

    private VariableResolver() {
    }

    public static String substitute(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty() || variables == null || variables.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) continue;
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace("${" + entry.getKey() + "}", value);
        }
        return result;
    }
}
