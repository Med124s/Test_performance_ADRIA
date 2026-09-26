package com.loadpilot.backend.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Executeur unique partage pour (1) orchestrer chaque Execution de maniere
 * asynchrone (voir ExecutionServiceImpl) et (2) lancer chaque utilisateur
 * virtuel d'une meme Execution (voir HttpClientExecutionEngine).
 *
 * Un thread virtuel par tache (Executors.newVirtualThreadPerTaskExecutor,
 * JDK 21) - jamais un pool a taille fixe : le travail est 100% bloquant sur
 * de vraies I/O reseau (java.net.http.HttpClient), exactement le cas
 * d'usage pour lequel les threads virtuels ont ete concus (cout memoire
 * minimal par thread, pas de limite artificielle sur le nombre
 * d'utilisateurs virtuels concurrents au-dela des ressources reelles de la
 * machine). Voir rapport P0-A, comparaison des moteurs, pour la
 * justification complete du choix face a JMeter/Gatling.
 */
@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService loadTestExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
