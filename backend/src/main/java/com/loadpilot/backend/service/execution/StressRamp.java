package com.loadpilot.backend.service.execution;

import java.util.concurrent.TimeUnit;

/**
 * Mode stress : debit offert en hausse continue (echauffement et baseline a debit fixe, puis +vitesse de montee
 * chaque seconde) et reservoir d'utilisateurs virtuels agrandi selon le besoin. Un tick par seconde de test
 * (secondes de LiveStats) ; la montee s'arrete a une limite de LoadPilot, tenue HOLD_AT_LIMIT_SECONDS.
 */
public final class StressRamp {

    /** Limites de charge en vigueur au lancement (ExecutionLimitsService) et vitesse de montee du scenario (req/s par seconde). */
    public record Limits(int maxVirtualUsers, int maxRps, int maxDurationSeconds, int rampRpsPerSecond) {

        public Limits(int maxVirtualUsers, int maxRps, int maxDurationSeconds) {
            this(maxVirtualUsers, maxRps, maxDurationSeconds, StressSettings.DEFAULT_RAMP_RPS_PER_SECOND);
        }
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

    /** Croissance du reservoir sur une seconde ; "behind" : le besoin depassait la croissance permise (LoadPilot en retard). */
    record Growth(int add, boolean behind) {
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
    /** Secondes ou le reservoir etait en retard sur le besoin (anneau, comme LiveStats) : seconde + 1, 0 = jamais. */
    private static final int BEHIND_SLOTS = 128;
    private final long[] behindSeconds = new long[BEHIND_SLOTS];

    StressRamp(Limits limits, PacingGate gate, LiveStats live, Pool pool) {
        this.limits = limits;
        this.gate = gate;
        this.live = live;
        this.pool = pool;
    }

    /** Debit offert (req/s) a la seconde "second" du test, borne par le debit cible maximal. */
    static double offeredRpsAt(long second, int rampRpsPerSecond, int maxRps) {
        long rampStart = StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS;
        double rps = second < rampStart ? StressSettings.BASELINE_RPS
                : StressSettings.BASELINE_RPS + (double) rampRpsPerSecond * (second - rampStart + 1);
        return Math.min(rps, maxRps);
    }

    /**
     * Croissance du reservoir : le plus grand de deux besoins. Loi de Little sur le debit offert de la seconde suivante
     * ("required" VUs, marge comprise), qui anticipe la montee ; deficit mesure : chaque VU fournit achievedRps / current
     * req/s, les creneaux manques en demandent missedPerSecond / (achievedRps / current) de plus (think time compris).
     * Croissance bornee par MAX_POOL_GROWTH_PERCENT (au moins MIN_POOL_GROWTH) et la limite.
     */
    static Growth growth(long missedPerSecond, int current, double achievedRps, int required, int maxVirtualUsers) {
        if (current >= maxVirtualUsers) return new Growth(0, false);
        double perVirtualUser = current > 0 ? achievedRps / current : 0;
        long deficit = missedPerSecond <= 0 ? 0
                : perVirtualUser > 0 ? (long) Math.ceil(missedPerSecond / perVirtualUser) : missedPerSecond;
        long wanted = Math.max(deficit, (long) required - current);
        if (wanted <= 0) return new Growth(0, false);
        long growthCap = Math.max(StressSettings.MIN_POOL_GROWTH, (long) Math.ceil(current * StressSettings.MAX_POOL_GROWTH_PERCENT / 100.0));
        int add = (int) Math.min(Math.min(wanted, growthCap), maxVirtualUsers - current);
        return new Growth(add, add < wanted);
    }

    /** Besoin de la loi de Little : debit x temps de reponse moyen, plus la marge ; 0 sans reponse mesuree. */
    static int littleRequired(double rps, Long avgResponseTimeMs) {
        if (avgResponseTimeMs == null || avgResponseTimeMs <= 0) return 0;
        return (int) Math.ceil(rps * avgResponseTimeMs / 1000.0 * (100 + StressSettings.POOL_HEADROOM_PERCENT) / 100.0);
    }

    /** Une seconde de test : debit offert, reservoir, niveau de charge note, limite eventuelle. */
    void tick(long second) {
        if (second >= limits.maxDurationSeconds()) {
            limitReached = Limit.DURATION;
            return;
        }
        double target = holding != null ? heldRps : offeredRpsAt(second, limits.rampRpsPerSecond(), limits.maxRps());
        gate.setRate(target);
        offeredRps = target;
        peakOfferedRps = Math.max(peakOfferedRps, target);

        long missed = gate.drainMissedSlots();
        int current = pool.virtualUsers();
        LiveStats.Window recent = live.window(second - StressSettings.ACHIEVED_RATE_SECONDS, second);
        // Besoin anticipe sur la seconde suivante pendant la montee seulement : un VU ajoute reserve ses creneaux au debit
        // actuel, et ceux reserves aux 2 req/s de la baseline le feraient attendre plusieurs secondes.
        double next = holding == null && second >= StressSettings.WARMUP_SECONDS + StressSettings.BASELINE_SECONDS
                ? offeredRpsAt(second + 1, limits.rampRpsPerSecond(), limits.maxRps()) : target;
        Growth growth = growth(missed, current, recent.throughputRps(), littleRequired(next, recent.avgResponseTimeMs()),
                limits.maxVirtualUsers());
        if (growth.add() > 0) pool.add(growth.add());
        if (growth.behind()) markBehind(second);
        int size = current + growth.add();
        peakVirtualUsers = Math.max(peakVirtualUsers, size);
        poolAtLimit = size >= limits.maxVirtualUsers();
        live.recordLoadLevel(target);

        if (holding == null) {
            if (target >= limits.maxRps()) holding = Limit.TARGET_RPS;
            else if (poolAtLimit && (missed > 0 || growth.behind())) holding = Limit.VIRTUAL_USERS;
            if (holding != null) {
                holdSince = second;
                heldRps = target;
            }
        }
        if (holding != null && second - holdSince >= StressSettings.HOLD_AT_LIMIT_SECONDS) limitReached = holding;
    }

    private void markBehind(long second) {
        synchronized (behindSeconds) {
            behindSeconds[(int) (second % BEHIND_SLOTS)] = second + 1;
        }
    }

    /** Le reservoir a ete en retard sur le besoin pendant au moins une seconde de [fromSecond, toSecond[. */
    public boolean poolBehind(long fromSecond, long toSecond) {
        synchronized (behindSeconds) {
            for (long s = Math.max(0, fromSecond); s < toSecond; s++) {
                if (behindSeconds[(int) (s % BEHIND_SLOTS)] == s + 1) return true;
            }
        }
        return false;
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
