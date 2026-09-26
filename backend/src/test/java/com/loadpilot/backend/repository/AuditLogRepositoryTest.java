package com.loadpilot.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.dto.request.AuditLogFilterRequest;
import com.loadpilot.backend.entity.AuditLog;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.specification.AuditLogSpecifications;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifie la persistance reelle d'AuditLog (H2 + Liquibase 008, voir
 * application-test.yml) et le filtrage dynamique via
 * specification.AuditLogSpecifications - chaque test genere un
 * userId unique (UUID) pour rester isole malgre la base H2 partagee entre
 * classes de test dans le meme fork Surefire (voir MetricRepositoryTest).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AuditLogRepositoryTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    private AuditLog save(String userId, String username, AuditAction action, AuditModule module,
            AuditResult result, Instant date, String description) {
        return auditLogRepository.saveAndFlush(AuditLog.builder()
                .userId(userId)
                .username(username)
                .action(action)
                .module(module)
                .result(result)
                .date(date)
                .ip("127.0.0.1")
                .description(description)
                .build());
    }

    private String uniqueUser() {
        return "user-" + UUID.randomUUID();
    }

    private AuditLogFilterRequest emptyFilter() {
        return new AuditLogFilterRequest(null, null, null, null, null, null, null);
    }

    @Test
    void save_persistsAllFieldsWithGeneratedUuid() {
        String userId = uniqueUser();
        Instant now = Instant.now();
        AuditLog saved = save(userId, "alice", AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS, now, "Application created: x");

        assertThat(saved.getId()).isNotNull();
        AuditLog reloaded = auditLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUserId()).isEqualTo(userId);
        assertThat(reloaded.getUsername()).isEqualTo("alice");
        assertThat(reloaded.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(reloaded.getModule()).isEqualTo(AuditModule.APPLICATION);
        assertThat(reloaded.getResult()).isEqualTo(AuditResult.SUCCESS);
        assertThat(reloaded.getIp()).isEqualTo("127.0.0.1");
        assertThat(reloaded.getDescription()).isEqualTo("Application created: x");
    }

    @Test
    void save_withoutUserOrDescription_isAllowed() {
        AuditLog saved = save(null, null, AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, Instant.now(), null);

        AuditLog reloaded = auditLogRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUserId()).isNull();
        assertThat(reloaded.getUsername()).isNull();
        assertThat(reloaded.getDescription()).isNull();
    }

    @Test
    void specification_filterByUserId_returnsOnlyMatching() {
        String userId = uniqueUser();
        AuditLog matching = save(userId, "x", AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS, Instant.now(), "d");
        save(uniqueUser(), "y", AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS, Instant.now(), "d");

        List<AuditLog> result = auditLogRepository.findAll(
                AuditLogSpecifications.withFilters(new AuditLogFilterRequest(userId, null, null, null, null, null, null)));

        assertThat(result).extracting(AuditLog::getId).containsExactly(matching.getId());
    }

    @Test
    void specification_filterByUsername_returnsOnlyMatching() {
        String username = "uname-" + UUID.randomUUID();
        AuditLog matching = save(uniqueUser(), username, AuditAction.UPDATE, AuditModule.SCENARIO, AuditResult.SUCCESS, Instant.now(), "d");
        save(uniqueUser(), "someone-else", AuditAction.UPDATE, AuditModule.SCENARIO, AuditResult.SUCCESS, Instant.now(), "d");

        List<AuditLog> result = auditLogRepository.findAll(
                AuditLogSpecifications.withFilters(new AuditLogFilterRequest(null, username, null, null, null, null, null)));

        assertThat(result).extracting(AuditLog::getId).containsExactly(matching.getId());
    }

    @Test
    void specification_filterByActionAndModule_appliesAndLogic() {
        String userId = uniqueUser();
        AuditLog matching = save(userId, "u", AuditAction.DELETE, AuditModule.STEP, AuditResult.SUCCESS, Instant.now(), "d");
        save(userId, "u", AuditAction.DELETE, AuditModule.SCENARIO, AuditResult.SUCCESS, Instant.now(), "d");
        save(userId, "u", AuditAction.CREATE, AuditModule.STEP, AuditResult.SUCCESS, Instant.now(), "d");

        List<AuditLog> result = auditLogRepository.findAll(AuditLogSpecifications.withFilters(
                new AuditLogFilterRequest(userId, null, AuditAction.DELETE, AuditModule.STEP, null, null, null)));

        assertThat(result).extracting(AuditLog::getId).containsExactly(matching.getId());
    }

    @Test
    void specification_filterByResult_returnsOnlyMatching() {
        String userId = uniqueUser();
        AuditLog matching = save(userId, "u", AuditAction.EXECUTE, AuditModule.EXECUTION, AuditResult.FAILURE, Instant.now(), "d");
        save(userId, "u", AuditAction.EXECUTE, AuditModule.EXECUTION, AuditResult.SUCCESS, Instant.now(), "d");

        List<AuditLog> result = auditLogRepository.findAll(AuditLogSpecifications.withFilters(
                new AuditLogFilterRequest(userId, null, null, null, AuditResult.FAILURE, null, null)));

        assertThat(result).extracting(AuditLog::getId).containsExactly(matching.getId());
    }

    @Test
    void specification_filterByDateRange_returnsOnlyWithinRange() {
        String userId = uniqueUser();
        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);
        AuditLog tooEarly = save(userId, "u", AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, base, "d");
        AuditLog inRange = save(userId, "u", AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, base.plusSeconds(3600), "d");
        AuditLog tooLate = save(userId, "u", AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, base.plusSeconds(7200 + 60), "d");

        List<AuditLog> result = auditLogRepository.findAll(AuditLogSpecifications.withFilters(
                new AuditLogFilterRequest(userId, null, null, null, null, base.plusSeconds(1800), base.plusSeconds(5400))));

        assertThat(result).extracting(AuditLog::getId).containsExactly(inRange.getId());
        assertThat(result).extracting(AuditLog::getId).doesNotContain(tooEarly.getId(), tooLate.getId());
    }

    @Test
    void specification_combinedFilters_narrowsToExactMatch() {
        String userId = uniqueUser();
        AuditLog matching = save(userId, "combined", AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS, Instant.now(), "d");
        save(userId, "combined", AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.FAILURE, Instant.now(), "d");
        save(userId, "combined", AuditAction.UPDATE, AuditModule.APPLICATION, AuditResult.SUCCESS, Instant.now(), "d");

        List<AuditLog> result = auditLogRepository.findAll(AuditLogSpecifications.withFilters(
                new AuditLogFilterRequest(userId, "combined", AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS, null, null)));

        assertThat(result).extracting(AuditLog::getId).containsExactly(matching.getId());
    }

    @Test
    void specification_noFilters_includesAllCreatedEntries() {
        String userId = uniqueUser();
        AuditLog first = save(userId, "u", AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, Instant.now(), "d");
        AuditLog second = save(userId, "u", AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, Instant.now(), "d");

        List<AuditLog> result = auditLogRepository.findAll(AuditLogSpecifications.withFilters(emptyFilter()));

        assertThat(result).extracting(AuditLog::getId).contains(first.getId(), second.getId());
    }

    @Test
    void specification_nonMatchingCombination_returnsEmpty() {
        String userId = uniqueUser();
        save(userId, "u", AuditAction.READ, AuditModule.DASHBOARD, AuditResult.SUCCESS, Instant.now(), "d");

        List<AuditLog> result = auditLogRepository.findAll(AuditLogSpecifications.withFilters(
                new AuditLogFilterRequest(userId, null, AuditAction.DELETE, null, null, null, null)));

        assertThat(result).isEmpty();
    }

    @Test
    void countByResult_countsOnlyMatchingResult() {
        String userId = uniqueUser();
        save(userId, "u", AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.FAILURE, Instant.now(), "d");
        List<AuditLog> failuresForUser = auditLogRepository.findAll(AuditLogSpecifications.withFilters(
                new AuditLogFilterRequest(userId, null, null, null, AuditResult.FAILURE, null, null)));

        assertThat(failuresForUser).hasSize(1);
    }
}
