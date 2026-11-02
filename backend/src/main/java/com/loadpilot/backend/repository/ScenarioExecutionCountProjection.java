package com.loadpilot.backend.repository;

import java.util.UUID;

/** Nombre d'executions d'un scenario. */
public interface ScenarioExecutionCountProjection {

    UUID getScenarioId();

    String getScenarioName();

    String getApplicationName();

    long getExecutionCount();

    long getSuccessCount();
}
