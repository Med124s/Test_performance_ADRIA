package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.RequestDetailMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Choix des requetes dont le detail est conserve (echecs par type d'erreur et echantillon), avec un plafond memoire par execution. */
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
