package com.loadpilot.backend.enums;

/** Distribution du CSV : une ligne par utilisateur virtuel (PER_VIRTUAL_USER) ou une nouvelle ligne a chaque iteration (PER_ITERATION). */
public enum CsvDistribution {
    PER_VIRTUAL_USER,
    PER_ITERATION
}
