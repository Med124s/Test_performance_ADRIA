package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.ApplicationStatus;

/** Identite minimale de l'Application au sein d'un ApplicationDashboardResponse
 * (jamais l'entite JPA elle-meme). status peut etre null (voir ApplicationStatus). */
public record ApplicationIdentityResponse(
        String id,
        String name,
        ApplicationStatus status
) {
}
