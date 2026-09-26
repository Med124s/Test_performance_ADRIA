package com.loadpilot.backend.mapper;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.service.report.ThroughputCalculator;
import java.math.BigDecimal;
import java.util.UUID;
import org.mapstruct.Named;

/**
 * Petites conversions partagees par plusieurs mappers MapStruct
 * (ApplicationMapper, ScenarioMapper...) - evite de dupliquer ces methodes
 * dans chaque mapper (voir @Mapper(uses = MapperSupport.class)).
 */
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

    /**
     * P1-C — debit reel (requetes reellement executees / duree en secondes)
     * pour ExecutionHistoryResponse.throughput - meme formule EXACTE que
     * MetricGenerationService/PerformanceStatisticsService (voir
     * ThroughputCalculator). "requetes reellement executees" =
     * successfulSteps + failedSteps (jamais totalSteps, qui peut etre
     * superieur si l'execution s'est arretee au premier echec - voir
     * Metric.errorRate pour la meme distinction deja etablie).
     */
    @Named("executionThroughput")
    public static BigDecimal executionThroughput(Execution execution) {
        int executed = execution.getSuccessfulSteps() + execution.getFailedSteps();
        return ThroughputCalculator.compute(executed, execution.getDuration());
    }
}
