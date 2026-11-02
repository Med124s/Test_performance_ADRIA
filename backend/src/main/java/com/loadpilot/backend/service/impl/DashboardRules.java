package com.loadpilot.backend.service.impl;

/** Seuils du tableau de bord : comparaison du P95 d'une execution avec l'execution precedente du meme scenario. */
public final class DashboardRules {

    /** Au-dela de +20 % de P95 par rapport a l'execution precedente : regression. */
    public static final double REGRESSION_PERCENT = 20.0;

    /** En dessous de 5 % d'ecart : ecart faible. */
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
        return isRegression(changePercent, REGRESSION_PERCENT);
    }

    /** Hausse strictement superieure au seuil de regression. */
    public static boolean isRegression(Double changePercent, double regressionPercent) {
        return changePercent != null && changePercent > regressionPercent;
    }

    /** Tendance : WORSE (hausse au-dela du seuil), BETTER (baisse au-dela du seuil), sinon SAME. */
    public static String trend(Double changePercent, double regressionPercent) {
        if (changePercent == null) return "NONE";
        if (changePercent > regressionPercent) return "WORSE";
        if (changePercent < -regressionPercent) return "BETTER";
        return "SAME";
    }

    /** Nombre au format francais : virgule, espace des milliers, au plus une decimale. */
    public static String format(Number value) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(java.util.Locale.FRANCE);
        f.setMaximumFractionDigits(1);
        f.setGroupingUsed(true);
        return f.format(value);
    }
}
