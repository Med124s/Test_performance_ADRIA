package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.exception.ResultSetTooLargeException;
import java.text.NumberFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Garde-fou des lectures qui chargent TOUS les resultats d'une execution (rapport, exports,
 * analyse dans le temps, comparaison, detail complet, metriques) : avant le chargement, le nombre
 * de resultats (un simple COUNT) est converti en memoire selon le poids de l'appel, et cette part
 * est reservee dans le budget global partage avec les executions en cours (ResultsMemoryBudget),
 * pour la duree de l'appel. Refus immediat, avec un message clair, plutot qu'un manque de memoire
 * qui toucherait tout le processus (dont les tests en cours).
 *
 * Reservations imbriquees sur un meme thread (un export appelle le rapport puis l'analyse dans le
 * temps) : seule la plus externe compte, son poids couvre l'ensemble.
 */
@Component
public class ResultLoadGuard {

    private static final Logger log = LoggerFactory.getLogger(ResultLoadGuard.class);

    /**
     * Poids : memoire reelle par resultat charge, avec la MEME marge (~x2) que celle des executions
     * (2,5 Ko par resultat pour un pic reel de ~1,3 Ko, voir ResultsMemoryGuard), rapportee aux
     * 2,5 Ko d'une unite du budget : poids = cout reel x 2 / 2,5 Ko. Le budget entier correspondant
     * a toute la memoire de la JVM, une lecture admise a la limite en laisse donc encore la moitie.
     * Estimations de structure, arrondies vers le haut.
     */
    public enum Load {
        /** Entites ExecutionStepResult et statistiques (~0,7 Ko). */
        REPORT(0.6, "le rapport"),
        /** Rapport + analyse dans le temps + fichier entier en memoire (~1,2 Ko). */
        EXPORT(1.0, "l'export du rapport"),
        /** Lignes en tableaux et leur copie en echantillons (~0,4 Ko). */
        TIMELINE(0.3, "l'analyse dans le temps"),
        /** Rapport de chaque execution comparee, gardes jusqu'a la fin de la comparaison (~0,7 Ko). */
        COMPARE(0.6, "la comparaison"),
        /** Entites, reponses et JSON de toutes les requetes (~1,5 Ko). */
        DETAIL_WITH_RESULTS(1.2, "le détail complet des requêtes"),
        /** Metric, reponses et JSON (~1,2 Ko). */
        METRICS(1.0, "les métriques");

        final double weight;
        final String label;

        Load(double weight, String label) {
            this.weight = weight;
            this.label = label;
        }
    }

    /** Reservation en cours ; close() la libere (try-with-resources). */
    public interface Reservation extends AutoCloseable {
        @Override
        void close();
    }

    private static final Reservation NONE = () -> { };
    /** Une reservation est-elle deja tenue par ce thread (appel imbrique) ? */
    private static final ThreadLocal<Boolean> HELD = ThreadLocal.withInitial(() -> false);

    private final ResultsMemoryBudget budget;

    public ResultLoadGuard(ResultsMemoryBudget budget) {
        this.budget = budget;
    }

    static long units(Load load, long results) {
        return results <= 0 ? 0 : (long) Math.ceil(results * load.weight);
    }

    /** Plus grand nombre de resultats que cette lecture peut charger, seule, sur ce serveur. */
    public long maxResults(Load load) {
        return (long) Math.floor(budget.maxResults() / load.weight);
    }

    /** Cette lecture tiendrait-elle seule dans le budget (sans rien reserver) ? */
    public boolean fitsAlone(Load load, long results) {
        return units(load, results) <= budget.maxResults();
    }

    /**
     * Reserve la memoire de "results" resultats pour cette lecture, ou leve
     * ResultSetTooLargeException : 413 si trop volumineuse meme seule, 409 si le budget est
     * occupe pour l'instant (tests en cours, autres lectures).
     */
    public Reservation reserve(Load load, long results) {
        if (HELD.get()) return NONE;
        long units = units(load, results);
        switch (budget.tryReserve(units)) {
            case TOO_LARGE -> {
                log.warn("Lecture refusee ({}) : {} resultats, limite {} pour cette lecture", load, results, maxResults(load));
                throw new ResultSetTooLargeException(HttpStatus.PAYLOAD_TOO_LARGE, tooLargeMessage(load, results, maxResults(load)));
            }
            case BUSY -> {
                log.warn("Lecture refusee ({}) : {} resultats, memoire occupee par les tests ou lectures en cours", load, results);
                throw new ResultSetTooLargeException(HttpStatus.CONFLICT, busyMessage(load, results));
            }
            default -> {
                if (units == 0) return NONE;
                HELD.set(true);
                return () -> {
                    HELD.set(false);
                    budget.release(units);
                };
            }
        }
    }

    static String tooLargeMessage(Load load, long results, long limit) {
        NumberFormat f = NumberFormat.getIntegerInstance(Locale.FRANCE);
        return "Exécution trop volumineuse pour " + load.label + " : " + f.format(results) + " requêtes, au-delà de ce que "
                + "la mémoire de ce serveur permet de charger en une fois (limite " + f.format(limit) + " requêtes). "
                + "Augmentez LOADPILOT_JVM_MAX_MEMORY pour y accéder.";
    }

    static String busyMessage(Load load, long results) {
        NumberFormat f = NumberFormat.getIntegerInstance(Locale.FRANCE);
        return "Serveur momentanément occupé : " + load.label + " de cette exécution (" + f.format(results) + " requêtes) "
                + "ne tient pas dans la mémoire encore disponible (tests en cours ou autres rapports ouverts). "
                + "Réessayez dans quelques instants.";
    }
}
