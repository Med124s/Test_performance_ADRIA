package com.loadpilot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loadpilot.backend.repository.ExecutionRepository;
import com.loadpilot.backend.service.execution.RunningExecutionRegistry;
import com.loadpilot.backend.service.impl.ReportSnapshotService;
import com.loadpilot.backend.service.impl.ResultsRetentionJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durees de conservation de test (LOADPILOT_*_RETENTION_OVERRIDE) : format, refus en profil prod,
 * et date limite reellement utilisee par la purge des resultats.
 */
class RetentionOverridesTest {

    private static RetentionOverrides overrides(String audit, String results, String details, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new RetentionOverrides(audit, results, details, environment);
    }

    @Test
    void empty_keepsTheDurationsOfTheSettings() {
        RetentionOverrides none = overrides("", "  ", null, "dev");
        assertThat(none.audit(365)).isEqualTo(new RetentionOverrides.Retention(Duration.ofDays(365), "365 jours"));
        assertThat(none.results(90)).isEqualTo(new RetentionOverrides.Retention(Duration.ofDays(90), "90 jours"));
        assertThat(none.requestDetails(30)).isEqualTo(new RetentionOverrides.Retention(Duration.ofDays(30), "30 jours"));
    }

    @Test
    void minutesHoursDays_replaceTheSettings_withAnExplicitLabel() {
        RetentionOverrides set = overrides("10m", "2h", "3d", "dev");
        assertThat(set.audit(365).duration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(set.audit(365).label()).isEqualTo("10 minutes (durée de test LOADPILOT_AUDIT_RETENTION_OVERRIDE)");
        assertThat(set.results(90).duration()).isEqualTo(Duration.ofHours(2));
        assertThat(set.results(90).label()).startsWith("2 heures (");
        assertThat(set.requestDetails(30).duration()).isEqualTo(Duration.ofDays(3));
        assertThat(set.requestDetails(30).label()).startsWith("3 jours (");
        assertThat(RetentionOverrides.label(Duration.ofSeconds(90))).isEqualTo("90 secondes");
        assertThat(RetentionOverrides.label(Duration.ofMinutes(1))).isEqualTo("1 minute");
    }

    @Test
    void invalidValue_refusesToStart_withTheVariableName() {
        // Sans unite, Spring lirait des millisecondes : refuse plutot que tout purger.
        for (String invalid : new String[] {"10", "abc", "0m", "-5m"}) {
            assertThatThrownBy(() -> overrides(invalid, "", "", "dev"))
                    .as(invalid).isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("LOADPILOT_AUDIT_RETENTION_OVERRIDE invalide ('" + invalid + "')");
        }
    }

    @Test
    void prodProfile_refusesAnyOverride_butStartsWithoutOne() {
        assertThatThrownBy(() -> overrides("10m", "", "", "prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("profil prod");
        assertThatThrownBy(() -> overrides("", "", "1d", "prod")).isInstanceOf(IllegalStateException.class);
        assertThat(overrides("", "", "", "prod").audit(365).duration()).isEqualTo(Duration.ofDays(365));
    }

    @Test
    void resultsPurge_usesTheOverrides_asCutoffDates() {
        ExecutionRepository executions = mock(ExecutionRepository.class);
        when(executions.findResultsToPurge(any(), any(Pageable.class))).thenReturn(List.of());
        when(executions.findRequestDetailsToPurge(any(), any(Pageable.class))).thenReturn(List.of());
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.resultsRetentionDays()).thenReturn(90);
        when(settings.requestDetailsRetentionDays()).thenReturn(30);
        ResultsRetentionJob job = new ResultsRetentionJob(executions, mock(ReportSnapshotService.class), settings,
                mock(AuditLogService.class), mock(RunningExecutionRegistry.class), mock(JdbcTemplate.class),
                mock(TransactionTemplate.class), overrides("", "30m", "10m", "dev"));
        Instant now = Instant.parse("2026-10-21T12:00:00Z");

        job.purge(now);

        verify(executions).findResultsToPurge(org.mockito.ArgumentMatchers.eq(now.minus(Duration.ofMinutes(30))), any(Pageable.class));
        verify(executions).findRequestDetailsToPurge(org.mockito.ArgumentMatchers.eq(now.minus(Duration.ofMinutes(10))), any(Pageable.class));
    }
}
