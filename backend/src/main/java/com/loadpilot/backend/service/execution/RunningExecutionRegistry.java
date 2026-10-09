package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.exception.ExecutionLimitExceededException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Registre en memoire des executions reellement actives dans CE processus
 * (une par Execution QUEUED/RUNNING). Permet a l'endpoint d'annulation de
 * retrouver la vraie RunningExecutionHandle a interrompre, ET (P0-B) fait
 * office de garde-fou de capacite (limites globales configurables) - un
 * seul et meme composant connait deja "combien d'executions/VUs tournent
 * reellement en ce moment", jamais une source de verite separee qui
 * pourrait diverger.
 *
 * LIMITE HONNETE (documentee, pas de solution proposee ici sans besoin
 * demontre) : ce registre est en memoire, pas persiste - voir
 * ExecutionRecoveryRunner pour la strategie reelle de recuperation au
 * demarrage (jamais une fausse reprise). Suppose un DEPLOIEMENT MONO-
 * INSTANCE (comme le reste de cette architecture a ce stade - aucun
 * mecanisme multi-instance n'existe ailleurs dans ce backend) : plusieurs
 * instances backend concurrentes auraient chacune leur propre compteur de
 * capacite, non partage - hors scope tant qu'un besoin reel de scale-out
 * horizontal n'est pas demontre (voir prompt P0-B, section 25 : "ne cree
 * pas un systeme complexe de distributed locking sans justification").
 *
 * Thread-safe (ConcurrentHashMap + verrou dedie pour les compteurs de
 * capacite) : plusieurs Executions concurrentes restent totalement isolees
 * les unes des autres, chacune avec sa propre entree/poignee/compteur de
 * VUs, jamais un etat partage entre elles.
 */
@Component
public class RunningExecutionRegistry {

    private record ActiveEntry(RunningExecutionHandle handle, int virtualUsers) {
    }

    private final Map<UUID, ActiveEntry> active = new ConcurrentHashMap<>();

    /** Protege UNIQUEMENT les deux compteurs ci-dessous (jamais la map
     * "active", deja thread-safe par elle-meme) - portee volontairement
     * minimale, verrou tres brievement tenu (arithmetique entiere pure). */
    private final Object capacityLock = new Object();
    private int reservedVirtualUsers = 0;
    private int reservedExecutionSlots = 0;

    /** Limites en vigueur, relues a chaque reservation : une limite modifiee dans Parametres
     * s'applique aux prochains lancements, jamais aux executions deja en cours. */
    private final java.util.function.IntSupplier maxGlobalVirtualUsersSupplier;
    private final java.util.function.IntSupplier maxConcurrentExecutionsSupplier;

    /** Limites fixes (tests unitaires). */
    public RunningExecutionRegistry(int maxGlobalVirtualUsers, int maxConcurrentExecutions) {
        this.maxGlobalVirtualUsersSupplier = () -> maxGlobalVirtualUsers;
        this.maxConcurrentExecutionsSupplier = () -> maxConcurrentExecutions;
    }

    /** Limites applicatives de Parametres > Systeme (jamais au-dessus des plafonds du serveur). */
    @org.springframework.beans.factory.annotation.Autowired
    public RunningExecutionRegistry(com.loadpilot.backend.service.ExecutionLimitsService limits) {
        this.maxGlobalVirtualUsersSupplier = () -> limits.value(com.loadpilot.backend.service.ExecutionLimitsService.Limit.VIRTUAL_USERS_TOTAL);
        this.maxConcurrentExecutionsSupplier = () -> limits.value(com.loadpilot.backend.service.ExecutionLimitsService.Limit.CONCURRENT_TESTS);
    }

    /**
     * Reserve reellement de la capacite AVANT meme qu'une ligne Execution
     * n'existe en base (voir ExecutionServiceImpl.execute) - le refus doit
     * etre deterministe et se produire AVANT toute creation, jamais une
     * Execution acceptee puis silencieusement bloquee (prompt P0-B, section
     * 7). Si cette methode leve, l'appelant ne doit RIEN persister.
     *
     * @throws ExecutionLimitExceededException si l'une des deux limites
     *         globales serait depassee ; message explicite (jamais generique).
     */
    public void reserveCapacity(int virtualUsers) {
        int maxGlobalVirtualUsers = maxGlobalVirtualUsersSupplier.getAsInt();
        int maxConcurrentExecutions = maxConcurrentExecutionsSupplier.getAsInt();
        synchronized (capacityLock) {
            if (virtualUsers > maxGlobalVirtualUsers) {
                // Jamais lancable, meme serveur au repos : ce n'est pas une limite « atteinte ».
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

    /**
     * Libere une reservation faite via reserveCapacity() qui n'a FINALEMENT
     * jamais abouti a une vraie Execution enregistree (ex : le Scenario n'a
     * plus d'etapes au moment de prepareAndStart) - sans cet appel, la
     * capacite reservee resterait perdue indefiniment (fuite) alors
     * qu'aucune execution reelle ne l'occupe.
     */
    public void releaseReservationWithoutRegistering(int virtualUsers) {
        synchronized (capacityLock) {
            reservedExecutionSlots--;
            reservedVirtualUsers -= virtualUsers;
        }
    }

    /** Attache la vraie poignee une fois que la ligne Execution existe
     * reellement en base (capacite deja reservee via reserveCapacity). */
    public RunningExecutionHandle registerReserved(UUID executionId, int virtualUsers) {
        RunningExecutionHandle handle = new RunningExecutionHandle();
        active.put(executionId, new ActiveEntry(handle, virtualUsers));
        return handle;
    }

    public RunningExecutionHandle get(UUID executionId) {
        ActiveEntry entry = active.get(executionId);
        return entry != null ? entry.handle() : null;
    }

    /** Retire l'execution du registre ET libere sa part de capacite globale -
     * doit etre appele exactement une fois par execution reellement
     * enregistree, quel que soit l'issue (voir ExecutionServiceImpl.runAsync,
     * bloc finally) - sans quoi la capacite resterait indefiniment occupee
     * par une execution pourtant terminee (fuite memoire/capacite). */
    public void unregister(UUID executionId) {
        ActiveEntry removed = active.remove(executionId);
        if (removed != null) {
            synchronized (capacityLock) {
                reservedExecutionSlots--;
                reservedVirtualUsers -= removed.virtualUsers();
            }
        }
    }

    /** Executions encore suivies par ce processus : en cours, OU terminees mais encore en train
     * d'enregistrer leurs resultats / metriques (la tache asynchrone n'est pas finie). */
    public java.util.Set<UUID> activeExecutionIds() {
        return java.util.Set.copyOf(active.keySet());
    }

    /** Nombre reel d'executions actives dans ce processus (jamais un
     * comptage approximatif) - utile pour l'observabilite/les tests. */
    public int activeExecutionCount() {
        return active.size();
    }

    /** Nombre reel d'utilisateurs virtuels actuellement reserves (executions
     * actives + reservations en cours de finalisation) - utile pour
     * l'observabilite/les tests. */
    public int reservedVirtualUsersSnapshot() {
        synchronized (capacityLock) {
            return reservedVirtualUsers;
        }
    }
}
