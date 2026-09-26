package com.loadpilot.backend.service.execution;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/**
 * Poignee reelle de controle d'une Execution en cours - un seul objet par
 * Execution, partage entre le thread qui orchestre l'execution (voir
 * ExecutionServiceImpl) et le thread HTTP qui traite une eventuelle
 * demande d'annulation (POST /api/executions/{id}/cancel).
 *
 * "cancelled" est verifie de maniere cooperative par chaque utilisateur
 * virtuel (voir HttpClientExecutionEngine.runVirtualUser) AVANT chaque
 * etape/iteration ; "futures" permet EN PLUS d'interrompre reellement une
 * requete HTTP deja en vol (Future.cancel(true) interrompt le thread
 * virtuel, java.net.http.HttpClient.send(...) reagit a l'interruption).
 * Sans ce deuxieme mecanisme, l'annulation attendrait la fin de la requete
 * HTTP en cours avant de prendre effet.
 */
public final class RunningExecutionHandle {

    private volatile boolean cancelled = false;
    private final List<Future<?>> futures = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled;
    }

    public void registerFuture(Future<?> future) {
        futures.add(future);
        // Cas limite reel : l'annulation a ete demandee ENTRE la creation de
        // cette poignee et l'enregistrement de ce future precis - sans ce
        // rattrapage, ce VU particulier ignorerait l'annulation deja actee.
        if (cancelled) {
            future.cancel(true);
        }
    }

    /** Demande une annulation REELLE : positionne le drapeau cooperatif ET
     * interrompt immediatement tous les utilisateurs virtuels deja lances. */
    public void requestCancellation() {
        cancelled = true;
        futures.forEach(f -> f.cancel(true));
    }
}
