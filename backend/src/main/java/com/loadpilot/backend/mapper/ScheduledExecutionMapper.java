package com.loadpilot.backend.mapper;

import com.loadpilot.backend.dto.response.ScheduledExecutionResponse;
import com.loadpilot.backend.entity.ScheduledExecution;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = MapperSupport.class)
public interface ScheduledExecutionMapper {

    @Mapping(target = "id", source = "id", qualifiedByName = "uuidToString")
    @Mapping(target = "scenarioId", source = "scenario.id", qualifiedByName = "uuidToString")
    @Mapping(target = "scenarioName", source = "scenario.name")
    @Mapping(target = "applicationId", source = "scenario.application.id", qualifiedByName = "uuidToString")
    @Mapping(target = "applicationName", source = "scenario.application.name")
    @Mapping(target = "createdByUsername", source = "createdBy", qualifiedByName = "appUserDisplayName")
    @Mapping(target = "lastExecutionId", source = "lastExecutionId", qualifiedByName = "uuidToString")
    ScheduledExecutionResponse toResponse(ScheduledExecution scheduledExecution);

    List<ScheduledExecutionResponse> toResponseList(List<ScheduledExecution> scheduledExecutions);
}
