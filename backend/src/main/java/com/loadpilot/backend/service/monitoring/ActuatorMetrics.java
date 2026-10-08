package com.loadpilot.backend.service.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lecture PURE d'une reponse Spring Boot Actuator /actuator/metrics/{nom} :
 * {"name":"process.cpu.usage","measurements":[{"statistic":"VALUE","value":0.12}],...}.
 * La valeur est dans le tableau "measurements" : statistique VALUE de preference, sinon la premiere.
 */
public final class ActuatorMetrics {

    private ActuatorMetrics() {
    }

    /** Valeur de la mesure, ou null si la reponse n'en contient pas (JSON illisible, tableau vide...). */
    public static Double value(ObjectMapper mapper, String json) {
        try {
            JsonNode measurements = mapper.readTree(json).path("measurements");
            if (!measurements.isArray() || measurements.isEmpty()) return null;
            JsonNode chosen = measurements.get(0);
            for (JsonNode m : measurements) {
                if ("VALUE".equalsIgnoreCase(m.path("statistic").asText())) {
                    chosen = m;
                    break;
                }
            }
            JsonNode value = chosen.path("value");
            return value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * URL de base Actuator a partir de la saisie : http://hote/actuator, http://hote/actuator/ ou
     * http://hote/actuator/metrics donnent tous http://hote/actuator.
     */
    public static String baseUrl(String url) {
        String base = url.trim().replaceAll("/+$", "");
        if (base.endsWith("/metrics")) base = base.substring(0, base.length() - "/metrics".length());
        return base;
    }
}
