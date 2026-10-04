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

    // Lus via la copie de l'etape (displayStep*) : l'historique reste lisible
    // meme si l'etape a ete supprimee depuis (step = null, migration 017).
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
