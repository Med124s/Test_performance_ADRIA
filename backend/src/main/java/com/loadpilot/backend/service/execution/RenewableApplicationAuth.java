package com.loadpilot.backend.service.execution;

import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authentification de l'Application partagee par tous les utilisateurs virtuels d'UNE Execution
 * (correction L5). Pour le mode « Connexion automatique » (LOGIN_TOKEN), le jeton obtenu au debut
 * peut expirer pendant un long test : sur un 401, le moteur demande ici un renouvellement.
 *
 *   - Un seul renouvellement a la fois : les utilisateurs virtuels qui recoivent un 401 en meme
 *     temps attendent le premier, puis reutilisent le nouveau jeton (numero de « generation »).
 *   - Echec de la reconnexion : aucune nouvelle tentative pendant {@link #RETRY_COOLDOWN_NANOS}
 *     pour ce jeton (jamais une rafale de connexions vers une page de login en panne).
 *   - Verrou ReentrantLock (jamais synchronized) : la reconnexion est une requete HTTP bloquante,
 *     et un bloc synchronized immobiliserait le thread porteur d'un thread virtuel.
 *   - Les autres modes (jeton fixe, cle API, Basic) ne sont jamais renouveles.
 */
public final class RenewableApplicationAuth {

    private static final Logger log = LoggerFactory.getLogger(RenewableApplicationAuth.class);

    /** Pause minimale entre deux tentatives de reconnexion apres un echec (5 s). */
    static final long RETRY_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** Jeton en vigueur et son numero de generation (0 = jeton obtenu au debut de l'Execution). */
    public record Current(ResolvedAuth resolved, long generation) {
    }

    private final ApplicationAuthSpec spec;
    private final String applicationBaseUrl;
    private final long timeoutSeconds;
    private final ApplicationAuthenticator authenticator;
    private final RequestDetailRecorder recorder;
    private final LongSupplier nanoClock;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Current current;
    private long failedGeneration = -1;
    private long failedAtNanos;
    private int renewals;

    public RenewableApplicationAuth(ApplicationAuthSpec spec, ResolvedAuth initial, String applicationBaseUrl, long timeoutSeconds,
                                    ApplicationAuthenticator authenticator, RequestDetailRecorder recorder) {
        this(spec, initial, applicationBaseUrl, timeoutSeconds, authenticator, recorder, System::nanoTime);
    }

    RenewableApplicationAuth(ApplicationAuthSpec spec, ResolvedAuth initial, String applicationBaseUrl, long timeoutSeconds,
                             ApplicationAuthenticator authenticator, RequestDetailRecorder recorder, LongSupplier nanoClock) {
        this.spec = spec;
        this.applicationBaseUrl = applicationBaseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.authenticator = authenticator;
        this.recorder = recorder;
        this.nanoClock = nanoClock;
        this.current = new Current(initial, 0);
    }

    /** Seul le mode « Connexion automatique » est renouvelable. */
    public boolean renewable() {
        return spec != null && spec.type() == ApplicationAuthType.LOGIN_TOKEN;
    }

    public Current current() {
        return current;
    }

    /** Nombre de renouvellements reussis depuis le debut de l'Execution. */
    public int renewals() {
        lock.lock();
        try {
            return renewals;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Jeton a utiliser apres un 401 obtenu avec {@code used} : celui deja renouvele par un autre
     * utilisateur virtuel, sinon un nouveau (une vraie reconnexion). null si la reconnexion a echoue
     * (ou vient d'echouer pour ce meme jeton) : l'appelant laisse alors l'echec remonter.
     */
    public Current renewAfter(Current used) {
        if (!renewable()) return null;
        lock.lock();
        try {
            Current now = current;
            if (now.generation() != used.generation()) {
                return now;
            }
            if (failedGeneration == used.generation() && nanoClock.getAsLong() - failedAtNanos < RETRY_COOLDOWN_NANOS) {
                return null;
            }
            try {
                ResolvedAuth fresh = authenticator.authenticate(spec, applicationBaseUrl, timeoutSeconds);
                Current next = new Current(fresh, now.generation() + 1);
                current = next;
                renewals++;
                maskInRequestDetails(fresh);
                log.info("Jeton de l'application renouvele apres un 401 (renouvellement n° {}).", renewals);
                return next;
            } catch (ApplicationAuthenticationException e) {
                failedGeneration = used.generation();
                failedAtNanos = nanoClock.getAsLong();
                // Messages construits sans secret (voir ApplicationAuthenticator).
                log.warn("Reconnexion a l'application impossible apres un 401 : {}", e.getMessage());
                return null;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Le nouveau jeton est masque dans le detail des requetes, comme le premier. */
    private void maskInRequestDetails(ResolvedAuth fresh) {
        if (recorder == null || !recorder.enabled()) return;
        Set<String> secrets = new LinkedHashSet<>(recorder.baseSecrets());
        secrets.addAll(StepTestRunner.resolvedSecrets(fresh));
        recorder.baseSecrets(secrets);
    }
}
