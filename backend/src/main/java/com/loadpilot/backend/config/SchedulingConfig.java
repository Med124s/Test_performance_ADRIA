package com.loadpilot.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Active les taches planifiees ({@code @Scheduled}). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
