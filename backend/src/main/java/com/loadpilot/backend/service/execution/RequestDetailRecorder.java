package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.RequestDetailMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Politique de conservation du detail des requetes d'UNE execution (partagee par tous les VU,
 * thread-safe). Le volume reste borne quelle que soit la taille du test :
 *
 *   - echecs : jusqu'a FAILURES_PER_TYPE par type d'erreur (code HTTP ou message different) et
 *     au plus FAILURES_PER_STEP par etape - une erreur rare qui arrive tard n'est pas perdue
 *     derriere des centaines d'erreurs identiques ;
 *   - echantillon (mode FAILURES_AND_SAMPLE) : les SAMPLE_ITERATIONS premieres iterations de
 *     chaque etape pour chaque utilisateur virtuel ;
 *   - corps limites a BODY_LIMIT_BYTES (mention « tronque ») ;
 *   - plafond memoire global : une fois atteint, l'echantillon des requetes reussies s'arrete
 *     (signale dans le detail de l'execution) mais les echecs continuent d'etre enregistres.
 */
public final class RequestDetailRecorder {

    public static final int SAMPLE_ITERATIONS = 3;
    public static final int BODY_LIMIT_BYTES = 10 * 1024;
    public static final int FAILURES_PER_TYPE = 20;
    public static final int FAILURES_PER_STEP = 100;

    private final RequestDetailMode mode;
    private final long maxBytes;
    private final Map<Integer, Integer> failuresByStep = new HashMap<>();
    private final Map<String, Integer> failuresByType = new HashMap<>();
    private long bytes;
    private volatile boolean sampleCapReached;
    private volatile Set<String> baseSecrets = Set.of();

    public RequestDetailRecorder(RequestDetailMode mode, long maxBytes) {
        this.mode = mode == null ? RequestDetailMode.NONE : mode;
        this.maxBytes = Math.max(0L, maxBytes);
    }

    public RequestDetailMode mode() {
        return mode;
    }

    public boolean enabled() {
        return mode != RequestDetailMode.NONE;
    }

    /** Secrets connus des le lancement (authentification de l'application) : toujours masques. */
    public void baseSecrets(Collection<String> secrets) {
        Set<String> copy = new LinkedHashSet<>();
        for (String s : secrets) if (s != null && !s.isBlank()) copy.add(s);
        this.baseSecrets = Set.copyOf(copy);
    }

    public Set<String> baseSecrets() {
        return baseSecrets;
    }

    /** Une requete reussie de cette iteration fait-elle partie de l'echantillon ? (avant de construire son detail) */
    public boolean wantsSample(int iteration) {
        return mode == RequestDetailMode.FAILURES_AND_SAMPLE && iteration < SAMPLE_ITERATIONS && !sampleCapReached;
    }

    /** Un echec de ce type, a cette etape, peut-il encore etre detaille ? (avant de construire son detail) */
    public synchronized boolean wantsFailure(int stepIndex, String failureType) {
        if (!enabled()) return false;
        return failuresByStep.getOrDefault(stepIndex, 0) < FAILURES_PER_STEP
                && failuresByType.getOrDefault(stepIndex + "|" + failureType, 0) < FAILURES_PER_TYPE;
    }

    /** Reserve la place d'un echec (quotas) ; jamais refuse par le plafond memoire. */
    public synchronized boolean acceptFailure(int stepIndex, String failureType, long approxBytes) {
        if (!wantsFailure(stepIndex, failureType)) return false;
        failuresByStep.merge(stepIndex, 1, Integer::sum);
        failuresByType.merge(stepIndex + "|" + failureType, 1, Integer::sum);
        bytes += approxBytes;
        return true;
    }

    /** Reserve la place d'un echantillon ; refuse (et arrete l'echantillon) au-dela du plafond memoire. */
    public synchronized boolean acceptSample(long approxBytes) {
        if (sampleCapReached || mode != RequestDetailMode.FAILURES_AND_SAMPLE) return false;
        if (bytes + approxBytes > maxBytes) {
            sampleCapReached = true;
            return false;
        }
        bytes += approxBytes;
        return true;
    }

    public boolean sampleCapReached() {
        return sampleCapReached;
    }

    public synchronized long bytes() {
        return bytes;
    }

    /** Type d'erreur : code HTTP recu, sinon message d'erreur (sans les parties variables longues). */
    public static String failureType(Integer httpStatus, String error) {
        if (httpStatus != null) return "HTTP " + httpStatus;
        if (error == null) return "erreur";
        String normalized = error.replaceAll("\\d+", "#");
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }

    /** Valeurs des variables dont le nom est sensible (password, token, secret, api_key…). */
    public static List<String> sensitiveValues(Map<String, String> variables) {
        return variables.entrySet().stream()
                .filter(e -> TestRunMasking.isSensitiveName(e.getKey()) && e.getValue() != null && !e.getValue().isBlank())
                .map(Map.Entry::getValue)
                .toList();
    }
}
