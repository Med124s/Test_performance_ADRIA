package com.loadpilot.backend.repository;

import com.loadpilot.backend.entity.NotificationPreference;
import com.loadpilot.backend.enums.NotificationType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    List<NotificationPreference> findByAppUserId(UUID appUserId);

    Optional<NotificationPreference> findByAppUserIdAndNotificationType(UUID appUserId, NotificationType notificationType);
}
