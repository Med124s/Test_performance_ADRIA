package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.MonitoringAuthType;
import com.loadpilot.backend.enums.MonitoringType;

/** Monitoring d'une application, sans le secret (simple indicateur « configuré »). */
public record ApplicationMonitoringResponse(
        MonitoringType type,
        String url,
        MonitoringAuthType authType,
        String headerName,
        boolean secretConfigured,
        int intervalSeconds
) {
}
