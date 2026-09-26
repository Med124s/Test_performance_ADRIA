package com.loadpilot.backend.dto.response;

import com.loadpilot.backend.enums.NotificationType;
import java.time.Instant;

public record NotificationResponse(
        String id,
        NotificationType type,
        String title,
        String message,
        String relatedExecutionId,
        String relatedScheduleId,
        boolean read,
        Instant createdAt,
        Instant readAt
) {
}
