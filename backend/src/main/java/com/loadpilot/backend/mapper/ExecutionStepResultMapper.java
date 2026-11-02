package com.loadpilot.backend.mapper;

import com.loadpilot.backend.dto.response.ExecutionStepResultResponse;
import com.loadpilot.backend.entity.ExecutionStepResult;
import com.loadpilot.backend.service.execution.UrlResolver;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Mapping des resultats ; toResponse() ajoute l'URL resolue. */
@Mapper(componentModel = "spring", uses = MapperSupport.class)
public abstract class ExecutionStepResultMapper {

    @Mapping(target = "stepId", expression = "java(result.stepIdOrNull() != null ? result.stepIdOrNull().toString() : null)")
    @Mapping(target = "stepName", expression = "java(result.displayStepName())")
    @Mapping(target = "method", expression = "java(result.displayStepMethod())")
    @Mapping(target = "url", ignore = true)
    abstract ExecutionStepResultResponse toResponseWithoutUrl(ExecutionStepResult result);

    public ExecutionStepResultResponse toResponse(ExecutionStepResult result, String applicationBaseUrl) {
        ExecutionStepResultResponse partial = toResponseWithoutUrl(result);
        String resolvedUrl = UrlResolver.resolve(applicationBaseUrl, result.displayStepUrl());
        return new ExecutionStepResultResponse(
                partial.stepId(), partial.stepName(), partial.method(), resolvedUrl,
                partial.httpStatus(), partial.responseTime(), partial.success(), partial.error(), partial.timestamp(),
                partial.virtualUser(), partial.iteration(), partial.requestUrl());
    }
}
