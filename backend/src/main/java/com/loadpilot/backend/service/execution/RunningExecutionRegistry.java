package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.exception.ExecutionLimitExceededException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Executions en cours dans ce processus et capacite utilisee (utilisateurs virtuels et executions simultanees). */
@Component
public class RunningExecutionRegistry {

    /** "stress" : test de stress, qui tourne seul sur le serveur. */
    private record ActiveEntry(RunningExecutionHandle handle, int virtualUsers, boolean stress) {
    }

    static final String STRESS_RUNNING = "Un test de stress est en cours : il tourne seul sur le serveur. "
            + "Réessayez quand il sera terminé.";
    static final String STRESS_SERIES_PAUSE = "Une série d'essais de stress est en pause entre deux essais : le serveur lui reste "
            + "réservé. Réessayez quand la série sera terminée.";

    private final Map<UUID, ActiveEntry> active = new ConcurrentHashMap<>();

    /** Protege les deux compteurs ci-dessous. */
    private final Object capacityLock = new Object();
    private int reservedVirtualUsers = 0;
    /** Un test de stress est reserve ou en cours. */
    private boolean stressReserved = false;
    private int reservedExecutionSlots = 0;
    /** Series d'essais en pause : reservation gardee entre deux essais (utilisateurs virtuels reserves), par serie. */
    private final Map<UUID, Integer> held = new java.util.HashMap<>();

    /** Limites en vigueur, relues a chaque reservation. */
    private final java.util.function.IntSupplier maxGlobalVirtualUsersSupplier;
    private final java.util.function.IntSupplier maxConcurrentExecutionsSupplier;

    /** Limites fixes (tests). */
    public RunningExecutionRegistry(int maxGlobalVirtualUsers, int maxConcurrentExecutions) {
        this.maxGlobalVirtualUsersSupplier = () -> maxGlobalVirtualUsers;
        this.maxConcurrentExecutionsSupplier = () -> maxConcurrentExecutions;
    }

    /** Limites de Parametres > Systeme. */
    @org.springframework.beans.factory.annotation.Autowired
    public RunningExecutionRegistry(com.loadpilot.backend.service.ExecutionLimitsService limits) {
        this.maxGlobalVirtualUsersSupplier = () -> limits.value(com.loadpilot.backend.service.ExecutionLimitsService.Limit.VIRTUAL_USERS_TOTAL);
        this.maxConcurrentExecutionsSupplier = () -> limits.value(com.loadpilot.backend.service.ExecutionLimitsService.Limit.CONCURRENT_TESTS);
    }

    /** Reserve la capacite avant la creation de l'execution ; ExecutionLimitExceededException si une limite serait depassee. */
    public void reserveCapacity(int virtualUsers) {
        reserveCapacity(virtualUsers, false);
    }

    /** "stress" : un test de stress n'est lance que si aucune autre execution n'est en cours, et bloque les suivantes. */
    public void reserveCapacity(int virtualUsers, boolean stress) {
        int maxGlobalVirtualUsers = maxGlobalVirtualUsersSupplier.getAsInt();
        int maxConcurrentExecutions = maxConcurrentExecutionsSupplier.getAsInt();
        synchronized (capacityLock) {
            if (stressReserved) throw new ExecutionLimitExceededException(held.isEmpty() ? STRESS_RUNNING : STRESS_SERIES_PAUSE);
            if (stress && reservedExecutionSlots > 0) {
                throw new ExecutionLimitExceededException("Un test de stress tourne seul sur le serveur : " + reservedExecutionSlots
                        + " exécution(s) en cours. Réessayez quand elles seront terminées.");
            }
            if (virtualUsers > maxGlobalVirtualUsers) {
                throw new ExecutionLimitExceededException(
                        "Ce test demande " + virtualUsers + " utilisateurs virtuels, plus que la limite d'un test sur ce serveur ("
                                + maxGlobalVirtualUsers + ", limité par la limite globale) : il ne pourra pas être lancé. "
                                + "Réduisez le nombre d'utilisateurs virtuels du scénario.");
            }
            if (reservedExecutionSlots >= maxConcurrentExecutions) {
                throw new ExecutionLimitExceededException(
                        "Nombre maximal de tests en même temps atteint (" + maxConcurrentExecutions
                                + "). Réessayez quand un test en cours sera terminé.");
            }
            if (reservedVirtualUsers + virtualUsers > maxGlobalVirtualUsers) {
                throw new ExecutionLimitExceededException(
                        "Limite globale d'utilisateurs virtuels atteinte : " + reservedVirtualUsers + " sur " + maxGlobalVirtualUsers
                                + " déjà utilisés par les tests en cours, ce test en demande " + virtualUsers
                                + ". Réessayez quand un test en cours sera terminé.");
            }
            reservedExecutionSlots++;
            reservedVirtualUsers += virtualUsers;
            if (stress) stressReserved = true;
        }
    }

    /** Libere une reservation qui n'a pas abouti a une execution. */
    public void releaseReservationWithoutRegistering(int virtualUsers) {
        releaseReservationWithoutRegistering(virtualUsers, false);
    }

    public void releaseReservationWithoutRegistering(int virtualUsers, boolean stress) {
        synchronized (capacityLock) {
            reservedExecutionSlots--;
            reservedVirtualUsers -= virtualUsers;
            if (stress) stressReserved = false;
        }
    }

    /** Enregistre l'execution creee (capacite deja reservee). */
    public RunningExecutionHandle registerReserved(UUID executionId, int virtualUsers) {
        return registerReserved(executionId, virtualUsers, false);
    }

    public RunningExecutionHandle registerReserved(UUID executionId, int virtualUsers, boolean stress) {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        handle.stressMode(stress);
        active.put(executionId, new ActiveEntry(handle, virtualUsers, stress));
        return handle;
    }

    public RunningExecutionHandle get(UUID executionId) {
        ActiveEntry entry = active.get(executionId);
        return entry != null ? entry.handle() : null;
    }

    /** Retire l'execution du registre et libere sa capacite. */
    public void unregister(UUID executionId) {
        ActiveEntry removed = active.remove(executionId);
        if (removed != null) {
            synchronized (capacityLock) {
                reservedExecutionSlots--;
                reservedVirtualUsers -= removed.virtualUsers();
                if (removed.stress()) stressReserved = false;
            }
        }
    }

    /**
     * Fin d'un essai de serie suivi d'un autre : l'execution quitte le registre mais sa reservation (test de stress
     * seul sur le serveur) est gardee pour l'essai suivant, sous la cle "seriesId".
     */
    public void unregisterKeepingReservation(UUID executionId, UUID seriesId) {
        ActiveEntry removed = active.remove(executionId);
        if (removed == null) return;
        synchronized (capacityLock) {
            held.put(seriesId, removed.virtualUsers());
        }
    }

    /** Essai suivant d'une serie : reprend la reservation gardee ; null si aucune (serie annulee pendant la pause). */
    public RunningExecutionHandle registerHeld(UUID seriesId, UUID executionId) {
        Integer virtualUsers;
        synchronized (capacityLock) {
            virtualUsers = held.remove(seriesId);
        }
        return virtualUsers == null ? null : registerReserved(executionId, virtualUsers, true);
    }

    /** Une reservation est gardee pour cette serie. */
    public boolean holds(UUID seriesId) {
        synchronized (capacityLock) {
            return held.containsKey(seriesId);
        }
    }

    /** Libere la reservation gardee pour cette serie (serie terminee ou annulee pendant la pause) ; sans effet sinon. */
    public void releaseHeld(UUID seriesId) {
        synchronized (capacityLock) {
            Integer virtualUsers = held.remove(seriesId);
            if (virtualUsers == null) return;
            reservedExecutionSlots--;
            reservedVirtualUsers -= virtualUsers;
            stressReserved = false;
        }
    }

    /** Executions encore suivies par ce processus (en cours, ou en train d'enregistrer leurs resultats). */
    public java.util.Set<UUID> activeExecutionIds() {
        return java.util.Set.copyOf(active.keySet());
    }

    /** Nombre d'executions actives. */
    public int activeExecutionCount() {
        return active.size();
    }

    /** Nombre d'utilisateurs virtuels reserves. */
    public int reservedVirtualUsersSnapshot() {
        synchronized (capacityLock) {
            return reservedVirtualUsers;
        }
    }
}
