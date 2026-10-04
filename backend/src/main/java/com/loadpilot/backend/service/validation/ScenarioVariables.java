package com.loadpilot.backend.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.exception.BadRequestException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Variables du scenario (type environnement Postman) : objet JSON {"nom": "valeur"} stocke dans
 * scenario.variables. Memes regles que le frontend (pages/scenario-wizard/variablesModel.ts) :
 * noms en lettres, chiffres et _ (sans commencer par un chiffre), 100 variables au maximum,
 * valeurs texte de 2000 caracteres au maximum.
 */
public final class ScenarioVariables {

    public static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_VARIABLES = 100;
    private static final int MAX_VALUE_LENGTH = 2000;

    private ScenarioVariables() {
    }

    /** Valide et normalise ; renvoie null pour « aucune variable ». */
    public static String normalize(String json) {
        Map<String, String> variables = parseStrict(json);
        if (variables.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(variables);
        } catch (Exception e) {
            throw new BadRequestException("Variables du scénario illisibles.");
        }
    }

    /** Lecture tolerante (moteur) : JSON absent ou illisible = aucune variable. */
    public static Map<String, String> parse(String json) {
        try {
            return parseStrict(json);
        } catch (BadRequestException e) {
            return Map.of();
        }
    }

    private static Map<String, String> parseStrict(String json) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return variables;
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new BadRequestException("Variables du scénario : JSON illisible.");
        }
        if (node == null || node.isNull()) return variables;
        if (!node.isObject()) throw new BadRequestException("Variables du scénario : un objet {\"nom\": \"valeur\"} est attendu.");
        if (node.size() > MAX_VARIABLES) throw new BadRequestException("100 variables du scénario au maximum.");
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            if (!NAME.matcher(name).matches()) {
                throw new BadRequestException("Variable du scénario « " + name
                        + " » : lettres, chiffres et _ uniquement, sans commencer par un chiffre.");
            }
            JsonNode value = entry.getValue();
            if (!value.isValueNode() || value.isNull()) {
                throw new BadRequestException("Variable du scénario « " + name + " » : la valeur doit être un texte.");
            }
            String text = value.asText();
            if (text.length() > MAX_VALUE_LENGTH) {
                throw new BadRequestException("Variable du scénario « " + name + " » : 2000 caractères au maximum.");
            }
            variables.put(name, text);
        }
        return variables;
    }
}
