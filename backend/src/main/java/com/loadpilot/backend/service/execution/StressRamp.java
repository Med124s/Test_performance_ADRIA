package com.loadpilot.backend.service.execution;

import java.util.concurrent.TimeUnit;

/**
 * Mode stress : debit offert en hausse continue (echauffement et baseline a debit fixe, puis +RAMP_RPS_PER_SECOND
 * chaque seconde) et reservoir d'utilisateurs virtuels agrandi selon le deficit. Un tick par seconde de test
 * (secondes de LiveStats) ; la montee s'arrete a une limite de LoadPilot, tenue HOLD_AT_LIMIT_SECONDS.
 */
public final class StressRamp {

    /** Limites de charge en vigueur au lancement (ExecutionLimitsService). */
    public record Limits(int maxVirtualUsers, int maxRps, int maxDurationSeconds) {
    }

    /** Limite de LoadPilot qui a mis fin a la montee. */
    public enum Limit {
        /** Debit cible maximal atteint et tenu. */
        TARGET_RPS,
        /** Utilisateurs virtuels au maximum, encore insuffisants pour le debit offert, et tenu. */
        VIRTUAL_USERS,
        /** Duree maximale d'un test. */
        DURATION
    }

    /** Reservoir d'utilisateurs virtuels (fourni par le moteur). */
    interface Pool {
        /** Utilisateurs virtuels crees depuis le debut. */
        int virtualUsers();

        void add(int count);
    }

    private final Limits limits;
    private final PacingGate gate;
    private final LiveStats live;
    private final Pool pool;

    private volatile double offeredRps;
    private volatile double peakOfferedRps;
    private volatile int peakVirtualUsers;
    private volatile boolean poolAtLimit;
    private volatile Limit limitReached;
    /** Limite atteinte et en cours de maintien : seconde de debut, et debit offert gele a ce moment. */
    private long holdSince = -1;
    private volatile Limit holding;
    private double heldRps;

    StressRamp(Limits limits, PacingGate gate, LiveStats live, Pool pool) {
        this.limits = limits;
        this.gate = gate;
        this.live = live;
        this.pool = pool;
    }

    /** Debit offert (req/s) a la seconde "second" du test, borne par le debit cible maximal. */
    static double offeredRpsAt(long second, int maxRps) {
        long rampStart = StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS;
        double rps = second < rampStart ? StressSettings.BASELINE_RPS
                : StressSettings.BASELINE_RPS + (double) StressSettings.RAMP_RPS_PER_SECOND * (second - rampStart + 1);
        return Math.min(rps, maxRps);
    }

    /**
     * Utilisateurs virtuels a ajouter : seulement si des creneaux ont ete manques alors qu'aucun n'attendait le sien
     * (tous occupes). Loi de Little : un utilisateur virtuel fournit achievedRps / current req/s, il en faut donc
     * missedPerSecond / (achievedRps / current) de plus ; croissance bornee par MAX_POOL_GROWTH_PERCENT et la limite.
     */
    static int virtualUsersToAdd(long missedPerSecond, int waiting, int current, double achievedRps, int maxVirtualUsers) {
        if (missedPerSecond <= 0 || waiting > 0 || current >= maxVirtualUsers) return 0;
        double perVirtualUser = current > 0 ? achievedRps / current : 0;
        long needed = perVirtualUser > 0 ? (long) Math.ceil(missedPerSecond / perVirtualUser) : missedPerSecond;
        long growthCap = Math.max(1, (long) Math.ceil(current * StressSettings.MAX_POOL_GROWTH_PERCENT / 100.0));
        return (int) Math.max(1, Math.min(Math.min(needed, growthCap), maxVirtualUsers - current));
    }

    /** Une seconde de test : debit offert, reservoir, niveau de charge note, limite eventuelle. */
    void tick(long second) {
        if (second >= limits.maxDurationSeconds()) {
            limitReached = Limit.DURATION;
            return;
        }
        double target = holding != null ? heldRps : offeredRpsAt(second, limits.maxRps());
        gate.setRate(target);
        offeredRps = target;
        peakOfferedRps = Math.max(peakOfferedRps, target);

        long missed = gate.drainMissedSlots();
        int current = pool.virtualUsers();
        double achieved = live.window(second - StressSettings.ACHIEVED_RATE_SECONDS, second).throughputRps();
        int add = virtualUsersToAdd(missed, gate.waiting(), current, achieved, limits.maxVirtualUsers());
        if (add > 0) pool.add(add);
        int size = current + add;
        peakVirtualUsers = Math.max(peakVirtualUsers, size);
        poolAtLimit = size >= limits.maxVirtualUsers();
        live.recordLoadLevel(target);

        if (holding == null) {
            if (target >= limits.maxRps()) holding = Limit.TARGET_RPS;
            else if (poolAtLimit && missed > 0) holding = Limit.VIRTUAL_USERS;
            if (holding != null) {
                holdSince = second;
                heldRps = target;
            }
        }
        if (holding != null && second - holdSince >= StressSettings.HOLD_AT_LIMIT_SECONDS) limitReached = holding;
    }

    /** Un tick au debut de chaque seconde jusqu'a une limite, l'annulation ou l'arret ; renvoie la limite atteinte (null sinon). */
    Limit run(RunningExecutionHandle handle) {
        long second = live.elapsedSeconds();
        while (!handle.isCancelled() && !handle.isStopRequested()) {
            tick(second);
            if (limitReached != null) return limitReached;
            long wakeNanos = live.startedNanos() + TimeUnit.SECONDS.toNanos(second + 1) - System.nanoTime();
            if (wakeNanos > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(wakeNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            second = Math.max(second + 1, live.elapsedSeconds());
        }
        return null;
    }

    public double offeredRps() {
        return offeredRps;
    }

    public double peakOfferedRps() {
        return peakOfferedRps;
    }

    public int peakVirtualUsers() {
        return peakVirtualUsers;
    }

    /** Reservoir a la limite d'utilisateurs virtuels : le critere de debit ne s'applique plus. */
    public boolean poolAtLimit() {
        return poolAtLimit;
    }

    /** La montee continue : debit offert encore en hausse (aucune limite atteinte ni en cours de maintien). */
    public boolean rising() {
        return holding == null && limitReached == null;
    }

    /** Limite atteinte et en cours de maintien (ou deja tenue), null tant que la montee continue. */
    public Limit holding() {
        return holding;
    }

    /** Utilisateurs virtuels crees jusqu'ici. */
    public int virtualUsers() {
        return pool.virtualUsers();
    }

    /** Limite qui a mis fin a la montee, null tant qu'elle continue. */
    public Limit limitReached() {
        return limitReached;
    }

    public Limits limits() {
        return limits;
    }
}
