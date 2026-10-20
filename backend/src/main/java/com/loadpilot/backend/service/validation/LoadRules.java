package com.loadpilot.backend.service.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Regles de charge d'un test, les memes partout : charge par defaut (Parametres > Systeme),
 * enregistrement et publication d'un scenario, planification, lancement d'une execution, et cote
 * interface (wizardModel.validateLoad, messages identiques mot pour mot) :
 *   - utilisateurs virtuels : obligatoire, 1 a la limite applicable ;
 *   - think time : obligatoire, 0 a 60 000 ms (plage fixe) ;
 *   - ramp-up : obligatoire, 0 a la duree maximale, et plus court que la duree quand elle est fixee
 *     (le moteur compte la duree depuis le debut du test) ;
 *   - duree, iterations, debit cible : facultatifs, 1 a leur limite.
 */
public final class LoadRules {

    public static final String RAMP_UP_TOO_LONG = "Le ramp-up doit être plus court que la durée.";
    public static final int MAX_THINK_TIME_MS = 60_000;
    static final String LIMIT_SUFFIX = " (limite d’un test sur ce serveur).";

    private LoadRules() {
    }

    /** Nombre au format francais avec espace des milliers : 3 600, 10 000. */
    public static String number(long value) {
        String digits = String.valueOf(Math.abs(value));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) out.append(' ');
            out.append(digits.charAt(i));
        }
        return (value < 0 ? "-" : "") + out;
    }

    public static String vuTooHigh(int max) {
        return "Le nombre d’utilisateurs ne doit pas dépasser " + number(max) + LIMIT_SUFFIX;
    }

    /** Messages de toutes les regles non respectees (vide = valide). */
    public static List<String> validate(Integer virtualUsers, Integer thinkTimeMs, Integer rampUpSeconds, Integer durationSeconds,
                                        Integer iterations, Integer targetRps, LoadLimits limits) {
        List<String> errors = new ArrayList<>();
        if (virtualUsers == null) errors.add("Le nombre d’utilisateurs est obligatoire.");
        else if (virtualUsers < 1) errors.add("Le nombre d’utilisateurs doit être au moins 1.");
        else if (virtualUsers > limits.maxVirtualUsers()) errors.add(vuTooHigh(limits.maxVirtualUsers()));
        if (thinkTimeMs == null) errors.add("Le think time est obligatoire.");
        else if (thinkTimeMs < 0) errors.add("Le think time doit être au moins 0.");
        else if (thinkTimeMs > MAX_THINK_TIME_MS) errors.add("Le think time ne doit pas dépasser " + number(MAX_THINK_TIME_MS) + " ms.");
        if (rampUpSeconds == null) errors.add("Le ramp-up est obligatoire.");
        else if (rampUpSeconds < 0) errors.add("Le ramp-up doit être au moins 0.");
        else if (rampUpSeconds > limits.maxDurationSeconds()) {
            errors.add("Le ramp-up ne doit pas dépasser " + number(limits.maxDurationSeconds()) + " s (durée maximale d’un test).");
        }
        if (durationSeconds != null && durationSeconds < 1) errors.add("La durée doit être au moins 1.");
        else if (durationSeconds != null && durationSeconds > limits.maxDurationSeconds()) {
            errors.add("La durée ne doit pas dépasser " + number(limits.maxDurationSeconds()) + " s" + LIMIT_SUFFIX);
        }
        if (iterations != null && iterations < 1) errors.add("Le nombre d’itérations doit être au moins 1.");
        else if (iterations != null && iterations > limits.maxIterations()) {
            errors.add("Le nombre d’itérations ne doit pas dépasser " + number(limits.maxIterations()) + LIMIT_SUFFIX);
        }
        if (targetRps != null && targetRps < 1) errors.add("Le débit cible doit être au moins 1.");
        else if (targetRps != null && targetRps > limits.maxTargetRps()) {
            errors.add("Le débit cible ne doit pas dépasser " + number(limits.maxTargetRps()) + " req/s" + LIMIT_SUFFIX);
        }
        String ramp = rampUpProblem(rampUpSeconds, durationSeconds);
        if (ramp != null) errors.add(ramp);
        return errors;
    }

    /**
     * Avertissements (jamais bloquants) : au-dela de limits.targetRpsAdvisedAboveVirtualUsers
     * utilisateurs virtuels sans debit cible, le debit n'est pas borne - la charge envoyee depend
     * alors de la vitesse de la cible, et la memoire de LoadPilot peut atteindre sa limite de
     * securite (le test s'arrete alors). Pas un refus : un scenario ou une planification existants ne
     * cessent jamais de fonctionner a cause de cette regle. Meme regle dans l'interface
     * (wizardModel.loadWarnings, message identique).
     */
    public static List<String> warnings(Integer virtualUsers, Integer targetRps, LoadLimits limits) {
        List<String> warnings = new ArrayList<>();
        int threshold = limits.targetRpsAdvisedAboveVirtualUsers();
        if (threshold > 0 && virtualUsers != null && virtualUsers > threshold && targetRps == null) {
            warnings.add(noTargetRpsWarning(threshold));
        }
        return warnings;
    }

    public static String noTargetRpsWarning(int threshold) {
        return "Plus de " + number(threshold) + " utilisateurs sans débit cible : le débit n’est pas borné. "
                + "Fixez un débit cible pour maîtriser la charge envoyée et la mémoire de LoadPilot.";
    }

    /** Ramp-up superieur ou egal a la duree fixee (null si correct ou non applicable). */
    public static String rampUpProblem(Integer rampUpSeconds, Integer durationSeconds) {
        return durationSeconds != null && durationSeconds >= 1 && rampUpSeconds != null && rampUpSeconds >= 0
                && rampUpSeconds >= durationSeconds ? RAMP_UP_TOO_LONG : null;
    }
}
