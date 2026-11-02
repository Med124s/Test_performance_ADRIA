package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.entity.Execution;

/** Resultat d'une tentative d'annulation et execution concernee. */
public record CancellationAttempt(CancellationOutcome outcome, Execution execution) {
}
