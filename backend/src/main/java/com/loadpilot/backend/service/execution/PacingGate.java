package com.loadpilot.backend.service.execution;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Master prompt final (Lot A) — regule REELLEMENT le debit global (tous
 * utilisateurs virtuels confondus) d'une Execution vers ~targetRps
 * demarrages de requete par seconde. Ne remplace jamais virtualUsers/
 * rampUpSeconds/thinkTimeMs (voir HttpClientExecutionEngine) : un SEUL
 * PacingGate est partage par tous les VUs d'une meme Execution, chacun
 * appelant acquire() juste avant CHAQUE requete HTTP.
 *
 * Algorithme (slot-allocation, aucune dependance ajoutee) : chaque appel
 * reserve atomiquement le prochain "slot" temporel (intervalNanos apres le
 * precedent), puis dort jusqu'a ce slot si necessaire. Approximatif par
 * nature (comme tout limiteur de debit cooperatif), mais borne dans le
 * temps par construction — jamais de blocage infini (le sommeil est
 * toujours une duree finie, interrompue immediatement par une annulation).
 */
final class PacingGate {

    private final long intervalNanos;
    private final AtomicLong nextSlotNanos;

    PacingGate(int targetRps) {
        this.intervalNanos = TimeUnit.SECONDS.toNanos(1) / targetRps;
        this.nextSlotNanos = new AtomicLong(System.nanoTime());
    }

    /** Bloque jusqu'au slot alloue a CET appel. Renvoie false si interrompu
     * (annulation reelle en cours, voir RunningExecutionHandle) — l'appelant
     * doit alors arreter immediatement plutot que d'envoyer la requete. */
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
