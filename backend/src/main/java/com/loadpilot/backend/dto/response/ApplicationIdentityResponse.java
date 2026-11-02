package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ApplicationStatus;

/** Identite d'une application dans son tableau de bord ; status peut etre null. */
public record ApplicationIdentityResponse(
        String id,
        String name,
        ApplicationStatus status
) {
}
