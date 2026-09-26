package com.loadpilot.backend.mapper;

import com.loadpilot.backend.dto.response.ExecutionHistoryResponse;
import com.loadpilot.backend.dto.response.ExecutionResponse;
import com.loadpilot.backend.entity.Execution;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = MapperSupport.class)
public interface ExecutionMapper {

    @Mapping(target = "id", source = "id", qualifiedByName = "uuidToString")
    @Mapping(target = "scenarioId", source = "scenario.id", qualifiedByName = "uuidToString")
    @Mapping(target = "scenarioName", source = "scenario.name")
    ExecutionResponse toResponse(Execution execution);

    List<ExecutionResponse> toResponseList(List<Execution> executions);

    /** P1-A — voir ExecutionHistoryResponse pour la justification complete
     * des champs presents/absents. P1-C : "triggeredByUsername"/"throughput"
     * desormais reellement disponibles (voir ExecutionHistoryResponse). */
    @Mapping(target = "id", source = "id", qualifiedByName = "uuidToString")
    @Mapping(target = "scenarioId", source = "scenario.id", qualifiedByName = "uuidToString")
    @Mapping(target = "scenarioName", source = "scenario.name")
    @Mapping(target = "applicationId", source = "scenario.application.id", qualifiedByName = "uuidToString")
    @Mapping(target = "applicationName", source = "scenario.application.name")
    @Mapping(target = "triggeredByUsername", source = "triggeredBy", qualifiedByName = "appUserDisplayName")
    @Mapping(target = "throughput", source = ".", qualifiedByName = "executionThroughput")
    ExecutionHistoryResponse toHistoryResponse(Execution execution);

    List<ExecutionHistoryResponse> toHistoryResponseList(List<Execution> executions);
}
