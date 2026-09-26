package com.loadpilot.backend.service.execution;

import java.util.Map;

/**
 * P1-Q Etape B — substitution REELLE de variables {@code ${nom}} dans
 * url/headers/body d'un Step (voir HttpClientExecutionEngine). Remplacement
 * litteral (String.replace, jamais une regex construite depuis une entree
 * utilisateur) : un nom de colonne CSV contenant un caractere special de
 * regex ne peut jamais casser ou detourner la substitution.
 *
 * Ne concerne PAS l'URL de base de l'Application : deja resolue reellement
 * par UrlResolver.resolve(), sans variable dediee necessaire.
 */
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
