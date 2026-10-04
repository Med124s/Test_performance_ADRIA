package com.loadpilot.backend.dto.request;

import java.util.Map;

/** Corps optionnel de POST /api/steps/{id}/test-run : variables a reutiliser (ex. {"transferId":"TRF..."}). */
public record StepTestRunRequest(Map<String, String> variables) {
}
