package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ApplicationStatus;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    /** Utilise par DashboardService (Phase 11) - le statut est nullable
     * (voir Application/ApplicationStatus) donc une Application jamais
     * testee ne compte dans aucun de ces totaux, seulement dans count(). */
    long countByStatus(ApplicationStatus status);
}
