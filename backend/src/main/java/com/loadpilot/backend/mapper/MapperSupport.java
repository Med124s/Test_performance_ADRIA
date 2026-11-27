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

    /** Resultat du test de stress ; null en mode manuel. */
    @Named("executionStress")
    public static com.loadpilot.backend.dto.response.StressResultResponse executionStress(Execution e) {
        if (e.getLoadMode() != com.loadpilot.backend.enums.LoadMode.STRESS) return null;
        java.util.List<String> criteria = e.getBreakpointCriteria() == null || e.getBreakpointCriteria().isBlank()
                ? java.util.List.of() : java.util.List.of(e.getBreakpointCriteria().split(","));
        return new com.loadpilot.backend.dto.response.StressResultResponse(e.getStressOutcome(), e.getStressLimit(),
                e.getStressMessage(), e.getStressPeakVirtualUsers(), e.getStressPeakOfferedRps(), criteria,
                e.getBreakpointOnsetSeconds(), e.getBreakpointDetectedSeconds(), e.getBreakpointVirtualUsers(),
                e.getBreakpointOfferedRps(), e.getBreakpointThroughputRps(), e.getBreakpointP95Ms(), e.getBaselineP95Ms(),
                e.getBreakpointRefusalPercent(), e.getStressRampStep());
    }

    @Named("executionThroughput")
    public static BigDecimal executionThroughput(Execution execution) {
        int executed = execution.getSuccessfulSteps() + execution.getFailedSteps();
        return ThroughputCalculator.compute(executed, execution.activeDurationMs());
    }
}
