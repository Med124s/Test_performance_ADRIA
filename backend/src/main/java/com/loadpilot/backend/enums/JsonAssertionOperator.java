package com.loadpilot.backend.enums;

/** Comparaison d'une assertion JSON d'etape (voir service.execution.StepChecks). */
public enum JsonAssertionOperator {
    /** La valeur lue est exactement la valeur attendue. */
    EQUALS,
    /** La valeur lue contient la valeur attendue. */
    CONTAINS
}
