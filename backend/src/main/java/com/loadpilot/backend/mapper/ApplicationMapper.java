package com.loadpilot.backend.mapper;

import com.loadpilot.backend.service.auth.ApplicationAuthSupport;
import com.loadpilot.backend.service.monitoring.MonitoringSupport;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.response.ApplicationResponse;
import com.loadpilot.backend.entity.Application;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring", uses = {MapperSupport.class, ApplicationAuthSupport.class, MonitoringSupport.class})
public interface ApplicationMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "authType", ignore = true)
    @Mapping(target = "authConfig", ignore = true)
    @Mapping(target = "statusMessage", ignore = true)
    @Mapping(target = "statusCheckedAt", ignore = true)
    @Mapping(target = "archived", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "monitoringType", ignore = true)
    @Mapping(target = "monitoringUrl", ignore = true)
    @Mapping(target = "monitoringAuthType", ignore = true)
    @Mapping(target = "monitoringHeaderName", ignore = true)
    @Mapping(target = "monitoringSecret", ignore = true)
    @Mapping(target = "monitoringIntervalSeconds", ignore = true)
    Application toEntity(ApplicationRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "authType", ignore = true)
    @Mapping(target = "authConfig", ignore = true)
    @Mapping(target = "statusMessage", ignore = true)
    @Mapping(target = "statusCheckedAt", ignore = true)
    @Mapping(target = "archived", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "monitoringType", ignore = true)
    @Mapping(target = "monitoringUrl", ignore = true)
    @Mapping(target = "monitoringAuthType", ignore = true)
    @Mapping(target = "monitoringHeaderName", ignore = true)
    @Mapping(target = "monitoringSecret", ignore = true)
    @Mapping(target = "monitoringIntervalSeconds", ignore = true)
    void updateEntityFromRequest(ApplicationRequest request, @MappingTarget Application application);

    @Mapping(target = "id", source = "id", qualifiedByName = "uuidToString")
    @Mapping(target = "createdBy", source = "createdBy", qualifiedByName = "appUserDisplayName")
    @Mapping(target = "auth", source = "application", qualifiedByName = "applicationAuthResponse")
    @Mapping(target = "monitoring", source = "application", qualifiedByName = "applicationMonitoringResponse")
    ApplicationResponse toResponse(Application application);

    List<ApplicationResponse> toResponseList(List<Application> applications);
}
