package com.loadpilot.backend.service.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ecriture des resultats en base pendant le test, par lots, dans un thread dedie. */
public final class ResultStream {

    private static final Logger log = LoggerFactory.getLogger(ResultStream.class);

    public static final int BATCH_SIZE = 100;
    public static final long FLUSH_INTERVAL_MS = 1000;
    static final int MAX_CLOSE_ATTEMPTS = 3;
    static final long RETRY_DELAY_MS = 500;

    /** Ecrit un lot en base, dans sa propre transaction ; leve une exception si rien n'a ete ecrit. */
    @FunctionalInterface
    public interface Writer {
        void write(List<StepOutcome> batch);
    }

    private final Writer writer;
    private final int batchSize;
    private final long flushIntervalMs;
    private final ResultSummary summary = new ResultSummary();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private final ArrayList<StepOutcome> buffer = new ArrayList<>();
    /** Taille du lot en cours d'ecriture (encore en memoire). */
    private int writing;
    private boolean closed;
    private long written;
    private long lost;
    private long dropped;
    private final Thread thread;

    public ResultStream(String name, Writer writer) {
        this(name, writer, BATCH_SIZE, FLUSH_INTERVAL_MS);
    }

    ResultStream(String name, Writer writer, int batchSize, long flushIntervalMs) {
        this.writer = writer;
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMs = Math.max(1, flushIntervalMs);
        this.thread = Thread.ofVirtual().name("results-writer-" + name).start(this::run);
    }

    /** Resultat d'un utilisateur virtuel : totaux mis a jour, ecriture en base differee. */
    public void accept(StepOutcome outcome) {
        lock.lock();
        try {
            if (closed) {
                // Utilisateur virtuel arrete apres la fin du test : resultat ignore.
                dropped++;
                return;
            }
            summary.add(outcome);
            buffer.add(outcome);
            if (buffer.size() >= batchSize) ready.signal();
        } finally {
            lock.unlock();
        }
    }

    public ResultSummary summary() {
        return summary;
    }

    /** Resultats encore en memoire (tampon et lot en cours d'ecriture). */
    public long inMemory() {
        lock.lock();
        try {
            return buffer.size() + writing;
        } finally {
            lock.unlock();
        }
    }

    public long written() {
        lock.lock();
        try {
            return written;
        } finally {
            lock.unlock();
        }
    }

    /** Resultats qui n'ont pas pu etre ecrits malgre les nouvelles tentatives (base indisponible). */
    public long lost() {
        lock.lock();
        try {
            return lost;
        } finally {
            lock.unlock();
        }
    }

    /** Ecrit les resultats restants puis arrete l'ecriture ; bloquant, idempotent. */
    public void close() {
        lock.lock();
        try {
            closed = true;
            ready.signal();
        } finally {
            lock.unlock();
        }
        boolean interrupted = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (dropped > 0) log.debug("{} resultat(s) recu(s) apres la fin du test, non enregistre(s).", dropped);
    }

    private void run() {
        int closeAttempts = 0;
        while (true) {
            List<StepOutcome> batch;
            boolean closing;
            lock.lock();
            try {
                long waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flushIntervalMs);
                while (!closed && buffer.size() < batchSize) {
                    long left = waitUntil - System.nanoTime();
                    if (left <= 0) break;
                    try {
                        ready.awaitNanos(left);
                    } catch (InterruptedException e) {
                        // Le thread s'arrete seulement a la fermeture.
                    }
                }
                closing = closed;
                if (buffer.isEmpty()) {
                    if (closing) return;
                    continue;
                }
                // Au plus un lot par transaction.
                int n = Math.min(batchSize, buffer.size());
                batch = new ArrayList<>(buffer.subList(0, n));
                buffer.subList(0, n).clear();
                writing = n;
            } finally {
                lock.unlock();
            }
            boolean ok = false;
            try {
                writer.write(batch);
                ok = true;
            } catch (Throwable e) {
                log.warn("Ecriture de {} resultat(s) en base echouee, nouvelle tentative : {}", batch.size(), e.getClass().getSimpleName());
            }
            lock.lock();
            try {
                writing = 0;
                if (ok) {
                    written += batch.size();
                    closeAttempts = 0;
                } else if (closing && ++closeAttempts >= MAX_CLOSE_ATTEMPTS) {
                    lost += batch.size();
                    closeAttempts = 0;
                    log.error("{} resultat(s) abandonne(s) apres {} tentatives d'ecriture.", batch.size(), MAX_CLOSE_ATTEMPTS);
                } else {
                    // Lot remis en tete du tampon, reessaye au prochain tour.
                    buffer.addAll(0, batch);
                }
            } finally {
                lock.unlock();
            }
            if (!ok) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ignored) {
                    // Le thread s'arrete seulement a la fermeture.
                }
            }
        }
    }
}
