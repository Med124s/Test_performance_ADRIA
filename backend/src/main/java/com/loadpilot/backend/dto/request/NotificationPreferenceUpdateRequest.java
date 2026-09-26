package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.NotNull;

/** P1-D — PATCH /api/notification-preferences/{type}. */
public record NotificationPreferenceUpdateRequest(
        @NotNull(message = "\"enabled\" est obligatoire.")
        Boolean enabled
) {
}
