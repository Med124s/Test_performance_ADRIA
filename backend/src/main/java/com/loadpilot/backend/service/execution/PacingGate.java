package com.loadpilot.backend.service.execution;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Regule le debit global d'une execution autour de targetRps demarrages de requete par seconde. */
final class PacingGate {

    private final long intervalNanos;
    private final AtomicLong nextSlotNanos;

    PacingGate(int targetRps) {
        this.intervalNanos = TimeUnit.SECONDS.toNanos(1) / targetRps;
        this.nextSlotNanos = new AtomicLong(System.nanoTime());
    }

    /** Attend le creneau de cet appel ; false si interrompu (annulation). */
    boolean acquire() {
        long mySlot = nextSlotNanos.getAndAdd(intervalNanos);
        long waitNanos = mySlot - System.nanoTime();
        if (waitNanos <= 0) {
            return true;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
