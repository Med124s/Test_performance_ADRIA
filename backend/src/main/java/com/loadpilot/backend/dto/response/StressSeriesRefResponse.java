package com.loadpilot.backend.dto.response;

/** Execution lancee dans une serie d'essais de stress : la serie et le rang de l'essai (1..n). */
public record StressSeriesRefResponse(String id, int index) {
}
