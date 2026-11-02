package com.loadpilot.backend.dto.request;

import jakarta.validation.constraints.AssertTrue;

/** Mise a jour d'une preference de notification. */
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
