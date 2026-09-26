package com.loadpilot.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * P1-B — active le support {@code @Scheduled} de Spring, necessaire au
 * poller reel de ScheduledExecution (voir service.impl.ScheduledExecutionPoller).
 * Classe dediee (plutot qu'ajoutee a une config existante) : une seule
 * responsabilite claire, facile a desactiver isolement dans un profil de
 * test qui ne voudrait pas du poller (voir application-test.yml si besoin
 * futur).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
