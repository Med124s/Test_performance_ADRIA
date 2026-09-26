package com.loadpilot.backend.mapper;

import com.loadpilot.backend.dto.request.StepRequest;
import com.loadpilot.backend.dto.response.StepResponse;
import com.loadpilot.backend.entity.Step;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring", uses = MapperSupport.class)
public interface StepMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "scenario", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Step toEntity(StepRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "scenario", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntityFromRequest(StepRequest request, @MappingTarget Step step);

    @Mapping(target = "id", source = "id", qualifiedByName = "uuidToString")
    @Mapping(target = "scenarioId", source = "scenario.id", qualifiedByName = "uuidToString")
    @Mapping(target = "scenarioName", source = "scenario.name")
    StepResponse toResponse(Step step);

    List<StepResponse> toResponseList(List<Step> steps);
}
