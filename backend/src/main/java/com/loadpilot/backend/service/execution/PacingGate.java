package com.loadpilot.backend.service.execution;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Regule le debit global d'une execution autour de targetRps demarrages de requete par seconde.
 * Debit fixe (mode manuel) : un creneau manque est rattrape. Debit reglable (mode stress) : un creneau manque
 * est abandonne et compte, ce qui signale un reservoir d'utilisateurs virtuels trop petit.
 */
final class PacingGate {

    private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

    private volatile long intervalNanos;
    private final AtomicLong nextSlotNanos;
    private final boolean adjustable;
    private final LongSupplier nanoClock;
    private final LongAdder missedSlots = new LongAdder();
    private final AtomicInteger waiting = new AtomicInteger();

    PacingGate(int targetRps) {
        this.intervalNanos = SECOND_NANOS / targetRps;
        this.nextSlotNanos = new AtomicLong(System.nanoTime());
        this.adjustable = false;
        this.nanoClock = System::nanoTime;
    }

    private PacingGate(double rps, LongSupplier nanoClock) {
        this.intervalNanos = intervalFor(rps);
        this.nanoClock = nanoClock;
        this.nextSlotNanos = new AtomicLong(nanoClock.getAsLong());
        this.adjustable = true;
    }

    /** Porte du mode stress : debit reglable en cours d'execution, creneaux manques comptes et non rattrapes. */
    static PacingGate adjustable(double rps) {
        return new PacingGate(rps, System::nanoTime);
    }

    /** "nanoClock" : horloge des creneaux (tests). */
    static PacingGate adjustable(double rps, LongSupplier nanoClock) {
        return new PacingGate(rps, nanoClock);
    }

    /** Change le debit (porte reglable uniquement) ; pris en compte au creneau suivant. */
    void setRate(double rps) {
        if (!adjustable) throw new IllegalStateException("Débit fixe : porte non réglable.");
        intervalNanos = intervalFor(rps);
    }

    double rate() {
        return (double) SECOND_NANOS / intervalNanos;
    }

    /** Utilisateurs virtuels qui attendent leur creneau en ce moment. */
    int waiting() {
        return waiting.get();
    }

    /** Creneaux passes sans preneur depuis le dernier appel (porte reglable) ; recale la porte si elle est en retard. */
    long drainMissedSlots() {
        realign(nanoClock.getAsLong());
        return missedSlots.sumThenReset();
    }

    /** Attend le creneau de cet appel ; false si interrompu (annulation). */
    boolean acquire() {
        long mySlot = adjustable ? takeSlot() : nextSlotNanos.getAndAdd(intervalNanos);
        long waitNanos = mySlot - nanoClock.getAsLong();
        if (waitNanos <= 0) {
            return true;
        }
        waiting.incrementAndGet();
        try {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            waiting.decrementAndGet();
        }
    }

    /** Porte reglable : prochain creneau, apres abandon des creneaux deja passes. */
    private long takeSlot() {
        while (true) {
            long now = nanoClock.getAsLong();
            realign(now);
            long slot = nextSlotNanos.get();
            if (nextSlotNanos.compareAndSet(slot, slot + intervalNanos)) return slot;
        }
    }

    /** Creneaux en retard de plus d'un intervalle : abandonnes, comptes, porte recalee sur "now". */
    private void realign(long now) {
        long interval = intervalNanos;
        long slot = nextSlotNanos.get();
        if (slot < now - interval && nextSlotNanos.compareAndSet(slot, now)) {
            missedSlots.add((now - slot) / interval);
        }
    }

    private static long intervalFor(double rps) {
        return Math.max(1L, Math.round(SECOND_NANOS / Math.max(rps, 0.001)));
    }
}
