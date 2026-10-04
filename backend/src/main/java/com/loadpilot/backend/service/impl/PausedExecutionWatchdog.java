package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.entity.Execution;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.repository.ExecutionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Annule automatiquement une execution restee en pause plus de
 * app.execution.max-pause-minutes (15 par defaut) : sans ce garde-fou, une pause
 * oubliee garderait indefiniment sa capacite (VU reserves) et son verrou sur le
 * scenario (modification/suppression refusees pendant une execution active).
 */
@Component
public class PausedExecutionWatchdog {

    private static final Logger log = LoggerFactory.getLogger(PausedExecutionWatchdog.class);

    private final ExecutionRepository executionRepository;
    private final ExecutionServiceImpl executionService;
    private final long maxPauseMinutes;

    public PausedExecutionWatchdog(ExecutionRepository executionRepository, ExecutionServiceImpl executionService,
            @Value("${app.execution.max-pause-minutes:15}") long maxPauseMinutes) {
        this.executionRepository = executionRepository;
        this.executionService = executionService;
        this.maxPauseMinutes = maxPauseMinutes;
    }

    public static String timeoutMessage(long minutes) {
        return "Exécution annulée automatiquement : elle est restée en pause plus de " + minutes + " minutes.";
    }

    @Scheduled(fixedDelayString = "${app.execution.pause-check-interval-ms:10000}")
    public void cancelExpiredPauses() {
        cancelPausedBefore(Instant.now().minus(Duration.ofMinutes(maxPauseMinutes)));
    }

    /** Annule les executions en pause depuis avant "threshold" ; renvoie leur nombre. */
    public int cancelPausedBefore(Instant threshold) {
        List<Execution> expired;
        try {
            expired = executionRepository.findByStatusAndPausedAtBefore(ExecutionStatus.PAUSED, threshold);
        } catch (RuntimeException e) {
            log.warn("Lecture des executions en pause echouee : {}", e.getClass().getSimpleName());
            return 0;
        }
        int cancelled = 0;
        for (Execution execution : expired) {
            try {
                executionService.cancelForPauseTimeout(execution.getId(), timeoutMessage(maxPauseMinutes));
                cancelled++;
            } catch (RuntimeException e) {
                log.warn("Annulation automatique de l'execution {} echouee : {}", execution.getId(), e.getClass().getSimpleName());
            }
        }
        return cancelled;
    }
}
