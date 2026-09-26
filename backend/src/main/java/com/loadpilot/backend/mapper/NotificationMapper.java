package com.loadpilot.backend.mapper;

import com.loadpilot.backend.dto.response.NotificationResponse;
import com.loadpilot.backend.entity.Notification;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = MapperSupport.class)
public interface NotificationMapper {

    @Mapping(target = "id", source = "id", qualifiedByName = "uuidToString")
    @Mapping(target = "relatedExecutionId", source = "relatedExecutionId", qualifiedByName = "uuidToString")
    @Mapping(target = "relatedScheduleId", source = "relatedScheduleId", qualifiedByName = "uuidToString")
    NotificationResponse toResponse(Notification notification);

    List<NotificationResponse> toResponseList(List<Notification> notifications);
}
