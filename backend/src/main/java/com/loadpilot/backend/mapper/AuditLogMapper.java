package com.loadpilot.backend.mapper;

import com.loadpilot.backend.dto.response.AuditLogResponse;
import com.loadpilot.backend.entity.AuditLog;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = MapperSupport.class)
public interface AuditLogMapper {

    @Mapping(target = "id", source = "id", qualifiedByName = "uuidToString")
    AuditLogResponse toResponse(AuditLog auditLog);

    List<AuditLogResponse> toResponseList(List<AuditLog> auditLogs);
}
