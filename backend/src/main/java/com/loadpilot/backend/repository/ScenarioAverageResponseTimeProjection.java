package com.loadpilot.backend.repository;

import java.util.UUID;

/**
 * P1-C — projection Spring Data pour la moyenne de temps de reponse PAR
 * scenario sur une plage temporelle (voir
 * MetricRepository#averageResponseTimeByScenarioIn, utilisee par le widget
 * "Top scenarios" du Dashboard) - une seule requete groupee, jamais une
 * requete AVG par scenario (pas de N+1).
 */
public interface ScenarioAverageResponseTimeProjection {

    UUID getScenarioId();

    Double getAverageResponseTime();
}
