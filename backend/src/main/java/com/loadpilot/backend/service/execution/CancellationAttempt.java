package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.entity.Execution;

/** Voir ExecutionTransactionHelper.attemptCancellation et CancellationOutcome. */
public record CancellationAttempt(CancellationOutcome outcome, Execution execution) {
}
