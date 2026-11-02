package com.loadpilot.backend.repository;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ApplicationStatus;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, UUID>, JpaSpecificationExecutor<Application> {

    List<Application> findByArchived(boolean archived, Sort sort);


    long countByStatus(ApplicationStatus status);
}
