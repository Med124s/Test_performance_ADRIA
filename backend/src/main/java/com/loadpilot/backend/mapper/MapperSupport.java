package com.loadpilot.backend.mapper;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.service.report.ThroughputCalculator;
import java.math.BigDecimal;
import java.util.UUID;
import org.mapstruct.Named;

/** Conversions partagees par les mappers MapStruct. */
public final class MapperSupport {

    private MapperSupport() {
    }

    @Named("uuidToString")
    public static String uuidToString(UUID id) {
        return id != null ? id.toString() : null;
    }

    /** Nom d'affichage du createur : username si connu, sinon son identifiant Keycloak. */
    @Named("appUserDisplayName")
    public static String appUserDisplayName(AppUser appUser) {
        if (appUser == null) {
            return null;
        }
        return appUser.getUsername() != null ? appUser.getUsername() : appUser.getKeycloakSubject();
    }

    /** Raison principale du statut (voir ExecutionStatusReason). */
    @Named("executionStatusReason")
    public static String executionStatusReason(Execution execution) {
        return com.loadpilot.backend.service.execution.ExecutionStatusReason.of(execution);
    }

    @Named("executionThroughput")
    public static BigDecimal executionThroughput(Execution execution) {
        int executed = execution.getSuccessfulSteps() + execution.getFailedSteps();
        return ThroughputCalculator.compute(executed, execution.activeDurationMs());
    }
}
