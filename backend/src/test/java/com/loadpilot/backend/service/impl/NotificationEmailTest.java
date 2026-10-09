package com.loadpilot.backend.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.enums.NotificationType;
import com.loadpilot.backend.mapper.NotificationMapper;
import com.loadpilot.backend.repository.AppUserRepository;
import com.loadpilot.backend.repository.NotificationRepository;
import com.loadpilot.backend.service.NotificationPreferenceService;
import com.loadpilot.backend.service.mail.MailService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Notifications par e-mail : envoyees seulement si choisies ET un serveur configure ; in-app independant. */
class NotificationEmailTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final NotificationPreferenceService preferences = mock(NotificationPreferenceService.class);
    private final MailService mail = mock(MailService.class);
    private final NotificationServiceImpl service = new NotificationServiceImpl(notifications, users, mock(NotificationMapper.class), preferences, mail);
    private final UUID userId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppUser user = new AppUser();
        user.setId(userId);
        user.setEmail("ingenieur@loadpilot.local");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(mail.available()).thenReturn(true);
        when(mail.appUrl()).thenReturn("http://localhost:3000");
        when(preferences.isEnabled(userId, NotificationType.EXECUTION_FAILED)).thenReturn(true);
    }

    @Test
    void emailChosen_sentWithReportLink_andStillInApp() {
        when(preferences.isEmailEnabled(userId, NotificationType.EXECUTION_FAILED)).thenReturn(true);
        service.create(userId, NotificationType.EXECUTION_FAILED, "Parcours : échoué", "Application injoignable", executionId, null);
        verify(mail).sendAsync(eq("ingenieur@loadpilot.local"), eq("[LoadPilot] Parcours : échoué"),
                contains("http://localhost:3000/executions/report/" + executionId));
        verify(notifications).save(any());
    }

    @Test
    void emailNotChosen_orNoServer_neverSent() {
        when(preferences.isEmailEnabled(userId, NotificationType.EXECUTION_FAILED)).thenReturn(false);
        service.create(userId, NotificationType.EXECUTION_FAILED, "t", "m", executionId, null);
        when(preferences.isEmailEnabled(userId, NotificationType.EXECUTION_FAILED)).thenReturn(true);
        when(mail.available()).thenReturn(false);
        service.create(userId, NotificationType.EXECUTION_FAILED, "t", "m", executionId, null);
        verify(mail, never()).sendAsync(anyString(), anyString(), anyString());
    }

    @Test
    void emailOnly_whenInAppDisabled() {
        when(preferences.isEnabled(userId, NotificationType.EXECUTION_FAILED)).thenReturn(false);
        when(preferences.isEmailEnabled(userId, NotificationType.EXECUTION_FAILED)).thenReturn(true);
        service.create(userId, NotificationType.EXECUTION_FAILED, "t", "m", executionId, null);
        verify(mail).sendAsync(anyString(), anyString(), anyString());
        verify(notifications, never()).save(any());
    }
}
