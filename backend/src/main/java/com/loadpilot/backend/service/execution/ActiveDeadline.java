package com.loadpilot.backend.service.execution;

/** Fin de la duree configuree, mesuree en temps actif (pauses exclues). */
record ActiveDeadline(long startNanos, long durationNanos, RunningExecutionHandle handle) {

    boolean reached() {
        return System.nanoTime() - startNanos - handle.pausedNanos() >= durationNanos;
    }

    /** Arrondi vers le HAUT : une attente de cette duree se termine a la fin du test, jamais juste avant. */
    long remainingMs() {
        long nanos = durationNanos - (System.nanoTime() - startNanos - handle.pausedNanos());
        return nanos <= 0 ? 0L : (nanos + 999_999L) / 1_000_000L;
    }
}
