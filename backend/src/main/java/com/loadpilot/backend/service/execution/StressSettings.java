package com.loadpilot.backend.service.execution;

/**
 * Mode stress : constantes internes de la montee en charge automatique et de la detection du point de rupture.
 * Jamais exposees a l'utilisateur ; les limites de charge (VUs par test, debit cible maximal, duree maximale)
 * restent celles d'ExecutionLimitsService.
 */
public final class StressSettings {

    // Montee en charge : echauffement, baseline, puis debit offert en hausse continue.

    /** Echauffement exclu des mesures (JIT, connexions, caches de la cible). */
    public static final int WARMUP_SECONDS = 10;
    /** Baseline mesuree juste apres l'echauffement. */
    public static final int BASELINE_SECONDS = 30;
    /** Debit offert pendant l'echauffement et la baseline (req/s). */
    public static final int BASELINE_RPS = 2;
    /** Hausse du debit offert apres la baseline (req/s gagnees chaque seconde). */
    public static final int RAMP_RPS_PER_SECOND = 1;
    /** Requetes minimales recues pendant la baseline pour qu'elle soit exploitable. */
    public static final int MIN_BASELINE_RESPONSES = 30;
    /** Maintien a une limite de LoadPilot avant de conclure « aucune rupture » : couvre la confirmation et les timeouts en cours. */
    public static final int HOLD_AT_LIMIT_SECONDS = 30;

    // Detection : chaque critere doit etre vrai sur CONFIRMATION_WINDOWS fenetres consecutives.

    /** Fenetre d'evaluation (secondes entieres et terminees). */
    public static final int WINDOW_SECONDS = 5;
    /** Fenetres consecutives en anomalie avant de conclure a une rupture. */
    public static final int CONFIRMATION_WINDOWS = 3;
    /** Requetes minimales dans une fenetre pour la juger. */
    public static final int MIN_WINDOW_REQUESTS = 20;
    /** Temps de reponse : P95 de la fenetre > RESPONSE_TIME_FACTOR x P95 de la baseline... */
    public static final double RESPONSE_TIME_FACTOR = 3.0;
    /** ...et > P95 de la baseline + RESPONSE_TIME_MARGIN_MS (une baseline de quelques ms ne declenche pas sur du bruit). */
    public static final long RESPONSE_TIME_MARGIN_MS = 200;
    /** Refus : timeouts, connexions refusees, 429 et 502/503/504 >= REFUSAL_PERCENT des requetes de la fenetre. */
    public static final double REFUSAL_PERCENT = 5.0;
    /** Debit : debit obtenu < THROUGHPUT_RATIO x debit offert, pendant que la montee continue et que le reservoir de VUs n'est pas a sa limite. */
    public static final double THROUGHPUT_RATIO = 0.8;

    // Protection de LoadPilot : seuil CPU = app.injector.cpu-warning-percent (85 % par defaut), comme l'alerte « Injecteur saturé ».

    /** Lectures consecutives du CPU de LoadPilot au-dessus du seuil avant l'arret « non concluant ». */
    public static final int INJECTOR_SATURATED_SAMPLES = 3;
    /** Intervalle de lecture du CPU de LoadPilot en mode stress (5 s sinon). */
    public static final int INJECTOR_INTERVAL_SECONDS = 2;

    private StressSettings() {
    }
}
