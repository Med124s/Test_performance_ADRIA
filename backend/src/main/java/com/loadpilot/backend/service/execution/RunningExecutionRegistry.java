package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.exception.ExecutionLimitExceededException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Executions en cours dans ce processus et capacite utilisee (utilisateurs virtuels et executions simultanees). */
@Component
public class RunningExecutionRegistry {

    private record ActiveEntry(RunningExecutionHandle handle, int virtualUsers) {
    }

    private final Map<UUID, ActiveEntry> active = new ConcurrentHashMap<>();

    /** Protege les deux compteurs ci-dessous. */
    private final Object capacityLock = new Object();
    private int reservedVirtualUsers = 0;
    private int reservedExecutionSlots = 0;

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
        int maxGlobalVirtualUsers = maxGlobalVirtualUsersSupplier.getAsInt();
        int maxConcurrentExecutions = maxConcurrentExecutionsSupplier.getAsInt();
        synchronized (capacityLock) {
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
        }
    }

    /** Libere une reservation qui n'a pas abouti a une execution. */
    public void releaseReservationWithoutRegistering(int virtualUsers) {
        synchronized (capacityLock) {
            reservedExecutionSlots--;
            reservedVirtualUsers -= virtualUsers;
        }
    }

    /** Enregistre l'execution creee (capacite deja reservee). */
    public RunningExecutionHandle registerReserved(UUID executionId, int virtualUsers) {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        active.put(executionId, new ActiveEntry(handle, virtualUsers));
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
            }
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
