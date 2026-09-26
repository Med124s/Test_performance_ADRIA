package com.loadpilot.backend.repository;

import java.util.UUID;

/**
 * P1-C — projection Spring Data (interface-based) pour le widget "Top
 * scenarios" du Dashboard (voir ExecutionRepository#findTopScenariosByExecutionCount) -
 * une seule requete groupee, jamais un chargement de toutes les Execution
 * suivies d'un comptage en memoire.
 */
public interface ScenarioExecutionCountProjection {

    UUID getScenarioId();

    String getScenarioName();

    String getApplicationName();

    long getExecutionCount();

    long getSuccessCount();
}
