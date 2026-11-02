package com.loadpilot.backend.repository;

import java.util.UUID;

/** Temps de reponse moyen d'un scenario. */
public interface ScenarioAverageResponseTimeProjection {

    UUID getScenarioId();

    Double getAverageResponseTime();
}
