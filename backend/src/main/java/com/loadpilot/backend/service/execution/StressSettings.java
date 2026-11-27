package com.loadpilot.backend.service.execution;

/**
 * Mode stress : constantes internes de la montee en charge automatique et de la detection du point de rupture.
 * Jamais exposees a l'utilisateur, sauf la vitesse de montee (reglage optionnel du scenario) ; les limites de charge
 * (VUs par test, debit cible maximal, duree maximale) restent celles d'ExecutionLimitsService.
 */
public final class StressSettings {

    // Montee en charge : echauffement, baseline, puis debit offert en hausse continue.

    /** Echauffement exclu des mesures (JIT, connexions, caches de la cible). */
    public static final int WARMUP_SECONDS = 10;
    /** Baseline mesuree juste apres l'echauffement. */
    public static final int BASELINE_SECONDS = 30;
    /** Debit offert pendant l'echauffement et la baseline (req/s). */
    public static final int BASELINE_RPS = 2;
    /**
     * Vitesse de montee apres la baseline (req/s gagnees chaque seconde) : seul reglage du mode stress, optionnel dans le
     * scenario. Le debut de la rupture est connu a une fenetre pres, soit WINDOW_SECONDS x la vitesse.
     */
    public static final int DEFAULT_RAMP_RPS_PER_SECOND = 1;
    public static final int MIN_RAMP_RPS_PER_SECOND = 1;
    public static final int MAX_RAMP_RPS_PER_SECOND = 50;
    /** Requetes minimales recues pendant la baseline pour qu'elle soit exploitable. */
    public static final int MIN_BASELINE_RESPONSES = 30;
    /** Maintien a une limite de LoadPilot avant de conclure « aucune rupture » : couvre la confirmation et les timeouts en cours. */
    public static final int HOLD_AT_LIMIT_SECONDS = 30;

    // Reservoir d'utilisateurs virtuels : agrandi chaque seconde selon le besoin (loi de Little) et le deficit.

    /** Utilisateurs virtuels au demarrage. */
    public static final int INITIAL_VIRTUAL_USERS = 1;
    /** Marge du besoin estime par la loi de Little (debit offert de la seconde suivante x temps de reponse moyen). */
    public static final int POOL_HEADROOM_PERCENT = 20;
    /** Croissance maximale du reservoir en une seconde : % des utilisateurs virtuels existants... */
    public static final int MAX_POOL_GROWTH_PERCENT = 100;
    /** ...et au moins ce nombre, pour qu'un petit reservoir suive une montee rapide. */
    public static final int MIN_POOL_GROWTH = 10;
    /** Secondes terminees qui mesurent le debit obtenu par utilisateur virtuel. */
    public static final int ACHIEVED_RATE_SECONDS = 3;

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
    /**
     * Debit : debit obtenu < THROUGHPUT_RATIO x debit offert, pendant que la montee continue et que le reservoir de VUs
     * suit (ni a sa limite, ni en retard sur le besoin pendant la fenetre).
     */
    public static final double THROUGHPUT_RATIO = 0.8;

    // Protection de LoadPilot : seuil CPU = app.injector.cpu-warning-percent (85 % par defaut), comme l'alerte « Injecteur saturé ».

    /** Lectures consecutives du CPU de LoadPilot au-dessus du seuil avant l'arret « non concluant ». */
    public static final int INJECTOR_SATURATED_SAMPLES = 3;
    /** Intervalle de lecture du CPU de LoadPilot en mode stress (5 s sinon). */
    public static final int INJECTOR_INTERVAL_SECONDS = 2;

    // Serie d'essais : le meme test de stress enchaine plusieurs fois, point de rupture consolide.

    /** Nombres d'essais proposes au lancement (1 = test seul, sans serie). */
    public static final java.util.List<Integer> SERIES_RUNS = java.util.List.of(1, 3, 5);
    /** Pause entre deux essais, pour que l'application testee recupere ; le serveur reste reserve a la serie. */
    public static final int SERIES_PAUSE_SECONDS = 30;
    /** Ecart maximal entre essais, (max - min) / moyenne, pour un point de rupture « confirme ». */
    public static final double SERIES_MAX_SPREAD_PERCENT = 20.0;

    /** Vitesse de montee du scenario ; null = DEFAULT_RAMP_RPS_PER_SECOND. */
    public static int rampOrDefault(Integer rampRpsPerSecond) {
        return rampRpsPerSecond != null ? rampRpsPerSecond : DEFAULT_RAMP_RPS_PER_SECOND;
    }

    /** Precision du debut de la rupture (± req/s) : la montee pendant une fenetre. */
    public static int precisionRps(int rampRpsPerSecond) {
        return WINDOW_SECONDS * rampRpsPerSecond;
    }

    private StressSettings() {
    }
}
