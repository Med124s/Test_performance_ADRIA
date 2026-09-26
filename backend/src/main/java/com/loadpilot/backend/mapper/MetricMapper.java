package com.loadpilot.backend.mapper;

import com.loadpilot.backend.dto.response.MetricResponse;
import com.loadpilot.backend.entity.Metric;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = MapperSupport.class)
public interface MetricMapper {

    @Mapping(target = "id", source = "id", qualifiedByName = "uuidToString")
    @Mapping(target = "applicationId", source = "application.id", qualifiedByName = "uuidToString")
    @Mapping(target = "scenarioId", source = "scenario.id", qualifiedByName = "uuidToString")
    @Mapping(target = "stepId", source = "step.id", qualifiedByName = "uuidToString")
    @Mapping(target = "executionId", source = "execution.id", qualifiedByName = "uuidToString")
    MetricResponse toResponse(Metric metric);

    List<MetricResponse> toResponseList(List<Metric> metrics);
}
