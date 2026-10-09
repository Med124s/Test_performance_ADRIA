package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.AssertTrue;

/** P1-D — PATCH /api/notification-preferences/{type}. */
public record NotificationPreferenceUpdateRequest(
        /** Dans l'application ; null = inchange. */
        Boolean enabled,
        /** Par e-mail ; null = inchange. */
        Boolean emailEnabled
) {
    @AssertTrue(message = "Indiquez au moins un canal : \"enabled\" ou \"emailEnabled\".")
    public boolean isSomethingToChange() {
        return enabled != null || emailEnabled != null;
    }
}
