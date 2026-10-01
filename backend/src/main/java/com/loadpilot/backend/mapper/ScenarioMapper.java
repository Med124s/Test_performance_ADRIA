package com.loadpilot.backend.mapper;

import com.loadpilot.backend.dto.request.ScenarioRequest;
import com.loadpilot.backend.dto.response.ScenarioResponse;
import com.loadpilot.backend.entity.Scenario;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring", uses = MapperSupport.class)
public interface ScenarioMapper {

    // virtualUsers/rampUpSeconds/durationSeconds/iterations/thinkTimeMs :
    // ignores ici volontairement - ScenarioServiceImpl les applique
    // explicitement avec les valeurs par defaut reelles (jamais un null
    // MapStruct copie tel quel dans une colonne NOT NULL, voir ScenarioRequest).
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "application", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "virtualUsers", ignore = true)
    @Mapping(target = "rampUpSeconds", ignore = true)
    @Mapping(target = "durationSeconds", ignore = true)
    @Mapping(target = "iterations", ignore = true)
    @Mapping(target = "thinkTimeMs", ignore = true)
    @Mapping(target = "stopMode", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Scenario toEntity(ScenarioRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "application", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "virtualUsers", ignore = true)
    @Mapping(target = "rampUpSeconds", ignore = true)
    @Mapping(target = "durationSeconds", ignore = true)
    @Mapping(target = "iterations", ignore = true)
    @Mapping(target = "thinkTimeMs", ignore = true)
    @Mapping(target = "stopMode", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntityFromRequest(ScenarioRequest request, @MappingTarget Scenario scenario);

    @Mapping(target = "id", source = "id", qualifiedByName = "uuidToString")
    @Mapping(target = "applicationId", source = "application.id", qualifiedByName = "uuidToString")
    @Mapping(target = "applicationName", source = "application.name")
    @Mapping(target = "createdBy", source = "createdBy", qualifiedByName = "appUserDisplayName")
    ScenarioResponse toResponse(Scenario scenario);

    List<ScenarioResponse> toResponseList(List<Scenario> scenarios);
}
