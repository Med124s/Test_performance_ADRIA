package com.loadpilot.backend.enums;

/**
 * Source du CPU et de la RAM du PROCESSUS de l'application testee pendant une execution :
 * NONE (aucune), PROMETHEUS (texte d'exposition, metriques standards process_*), ACTUATOR
 * (Spring Boot Actuator : process.cpu.usage et jvm.memory.used).
 */
public enum MonitoringType {
    NONE,
    PROMETHEUS,
    ACTUATOR
}
