package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotNull;

/** PATCH /api/executions/{id}/keep : true = conserver (jamais purgee), false = suivre la duree de conservation. */
public record ExecutionKeepRequest(@NotNull Boolean keep) {
}
