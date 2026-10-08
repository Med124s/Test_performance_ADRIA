package com.loadpilot.backend.service.impl;

/**
 * Seuils du Dashboard, definis en UN SEUL endroit.
 *
 * Comparaison du P95 d'une execution avec l'execution precedente (terminee, reussie ou echouee)
 * du meme scenario : ecart = (P95 - P95 precedent) / P95 precedent x 100.
 *   - WORSE  (rouge) : ecart > +SMALL_CHANGE_PERCENT ;
 *   - BETTER (vert)  : ecart < -SMALL_CHANGE_PERCENT ;
 *   - SAME   (gris)  : ecart faible (|ecart| <= SMALL_CHANGE_PERCENT) ;
 *   - NONE   (gris)  : pas d'execution precedente comparable (ou P95 inconnu).
 * Regression signalee dans « À surveiller » : ecart > REGRESSION_PERCENT.
 */
public final class DashboardRules {

    /** Au-dela de +20 % de P95 par rapport a l'execution precedente : regression. */
    public static final double REGRESSION_PERCENT = 20.0;

    /** En dessous de 5 % d'ecart (dans un sens ou dans l'autre) : ecart faible, affiche en gris. */
    public static final double SMALL_CHANGE_PERCENT = 5.0;

    private DashboardRules() {
    }

    /** Ecart du P95 en %, arrondi a 0,1 ; null sans reference exploitable. */
    public static Double changePercent(Long current, Long previous) {
        if (current == null || previous == null || previous <= 0) return null;
        return Math.round((current - previous) * 1000.0 / previous) / 10.0;
    }

    public static String trend(Double changePercent) {
        if (changePercent == null) return "NONE";
        if (changePercent > SMALL_CHANGE_PERCENT) return "WORSE";
        if (changePercent < -SMALL_CHANGE_PERCENT) return "BETTER";
        return "SAME";
    }

    public static boolean isRegression(Double changePercent) {
        return changePercent != null && changePercent > REGRESSION_PERCENT;
    }

    /** Nombres des textes du Dashboard au format francais : virgule, espace des milliers, au plus une decimale (2,4 ; 1 234). */
    public static String format(Number value) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(java.util.Locale.FRANCE);
        f.setMaximumFractionDigits(1);
        f.setGroupingUsed(true);
        return f.format(value);
    }
}
