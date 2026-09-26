package com.loadpilot.backend.mapper;

import com.loadpilot.backend.dto.response.ExecutionStepResultResponse;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.service.execution.UrlResolver;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Classe abstraite (pas une interface) : MapStruct genere le mapping des
 * champs simples via toResponseWithoutUrl(), et toResponse() complete
 * ensuite "url" avec la VRAIE url resolue (relative -> absolue via
 * Application.url) - impossible a exprimer comme une simple @Mapping
 * puisque cela necessite l'URL de base de l'Application, non portee par
 * ExecutionStepResult lui-meme (voir Phase 9).
 */
@Mapper(componentModel = "spring", uses = MapperSupport.class)
public abstract class ExecutionStepResultMapper {

    @Mapping(target = "stepId", source = "step.id", qualifiedByName = "uuidToString")
    @Mapping(target = "stepName", source = "step.name")
    @Mapping(target = "method", source = "step.method")
    @Mapping(target = "url", ignore = true)
    abstract ExecutionStepResultResponse toResponseWithoutUrl(ExecutionStepResult result);

    public ExecutionStepResultResponse toResponse(ExecutionStepResult result, String applicationBaseUrl) {
        ExecutionStepResultResponse partial = toResponseWithoutUrl(result);
        String resolvedUrl = UrlResolver.resolve(applicationBaseUrl, result.getStep().getUrl());
        return new ExecutionStepResultResponse(
                partial.stepId(), partial.stepName(), partial.method(), resolvedUrl,
                partial.httpStatus(), partial.responseTime(), partial.success(), partial.error(), partial.timestamp());
    }
}
