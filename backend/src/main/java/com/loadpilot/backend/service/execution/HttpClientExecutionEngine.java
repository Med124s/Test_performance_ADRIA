package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PostConstruct;
import com.loadpilot.backend.service.http.HttpClients;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implementation reelle unique (Java 21 java.net.http.HttpClient + threads
 * virtuels, aucune dependance ajoutee) - envoie de vraies requetes HTTP
 * concurrentes, un thread virtuel par utilisateur virtuel (VU). Voir
 * rapport P0-A pour le choix argumente face a JMeter/Gatling.
 *
 * COMPORTEMENT PAR DEFAUT (LoadTestSpec.singlePass(), soit un Scenario
 * jamais configure pour la charge) : EXACTEMENT le comportement historique
 * (Phase 9) - 1 VU, 1 iteration, sans think time. Aucune regression sur
 * les Scenario/Execution existants.
 *
 * POLITIQUE D'ARRET PAR ITERATION (documentee, Phase 9, inchangee) : au
 * sein d'UNE iteration d'UN VU, un Step qui echoue arrete cette iteration -
 * les Steps suivants de cette iteration ne sont pas executes. Les AUTRES
 * VUs, et les iterations SUIVANTES du meme VU, continuent normalement.
 *
 * POLITIQUE DE STATUT GLOBAL (decision documentee P0-A, section 28) :
 * PROBLEME - quel statut final pour une charge avec plusieurs VUs/
 * iterations, ou certaines requetes reussissent et d'autres echouent ?
 * OPTIONS - (a) FAILED des qu'au moins une requete echoue ; (b) FAILED
 * seulement si un seuil de taux d'erreur est depasse.
 * CHOIX - (a), par coherence stricte avec le comportement historique
 * (ou tout echec unique faisait deja FAILED) - (b) introduirait un seuil
 * arbitraire non demande. Le taux d'erreur reel reste consultable via
 * Metric.errorRate (jamais binaire, jamais invente).
 *
 * FORMAT DES HEADERS / EVALUATION DE SUCCES : inchanges (voir Phase 9).
 */
@Component
public class HttpClientExecutionEngine implements ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(HttpClientExecutionEngine.class);

    private final long timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final ExecutorService vuExecutor;
    private final ApplicationAuthenticator authenticator;
    /** Identifiant du proxy d'entreprise, ajoute aux requetes qui passent par lui (voir ProxyAuthorization). */
    private final com.loadpilot.backend.service.http.ProxyAuthorization proxyAuthorization;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpClientExecutionEngine(
            @Value("${app.execution.timeout-seconds:10}") long timeoutSeconds,
            ObjectMapper objectMapper,
            ExecutorService loadTestExecutor) {
        this(timeoutSeconds, objectMapper, loadTestExecutor, com.loadpilot.backend.service.http.ProxyAuthorization.current());
    }

    /** Identifiants de proxy fournis (tests). */
    HttpClientExecutionEngine(long timeoutSeconds, ObjectMapper objectMapper, ExecutorService loadTestExecutor,
            com.loadpilot.backend.service.http.ProxyAuthorization proxyAuthorization) {
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        this.vuExecutor = loadTestExecutor;
        this.proxyAuthorization = proxyAuthorization;
        this.authenticator = new ApplicationAuthenticator(objectMapper, proxyAuthorization);
    }

    /**
     * PROBLEME (constat REEL fait pendant cette phase, voir tests de
     * ramp-up) : sur une JVM tout juste demarree, le tout premier appel a
     * java.net.http.HttpClient.send(...) du processus initialise
     * paresseusement des ressources internes couteuses au niveau JDK (thread
     * gestionnaire de selecteur, pipeline de traitement des reponses). Ce
     * cout ponctuel (mesure : jusqu'a ~1s) peut a lui seul absorber tout
     * l'intervalle de ramp-up du tout premier test de charge lance apres
     * chaque demarrage du backend : les utilisateurs virtuels restent
     * "colles" les uns aux autres malgre des Thread.sleep(startDelayMs)
     * correctement espaces (voir runVirtualUser).
     * OPTIONS : (a) ne rien faire - risque reel et silencieux sur le
     * premier test de charge de chaque redemarrage ; (b) "rechauffer" via
     * une tentative de connexion vouee a l'echec (mesure pendant cette
     * phase : insuffisant, le spread reste parfois sous le seuil attendu) ;
     * (c) un vrai aller-retour HTTP reussi en local (loopback) au demarrage.
     * CHOIX : (c) - seul un aller-retour HTTP reellement complete
     * initialise de maniere fiable l'ensemble des ressources internes
     * concernees (mesure : spread ~700-800ms des le premier test sur JVM
     * froide avec ce warm-up, contre parfois <10ms sans lui). Cout paye une
     * seule fois au demarrage du backend (jamais pendant un test reel d'un
     * utilisateur), strictement local (loopback uniquement, aucune
     * exposition reseau), et strictement best-effort : toute exception est
     * capturee et journalisee en debug seulement - un warm-up manque
     * degraderait uniquement la precision du tout premier test, il ne doit
     * jamais empecher le demarrage du backend ni faire echouer quoi que ce
     * soit d'autre. Execute sur un thread virtuel dedie pour ne jamais
     * retarder le demarrage de l'application.
     */
    @PostConstruct
    void scheduleHttpClientWarmup() {
        Thread.ofVirtual().name("http-client-warmup").start(this::warmUpHttpClient);
    }

    /** Visibilite package-private (plutot que private) uniquement pour permettre a
     * HttpClientExecutionEngineTest de declencher un warm-up synchrone et bloquant
     * en @BeforeAll (une seule fois pour toute la classe de test, JVM partagee). */
    void warmUpHttpClient() {
        HttpServer warmServer = null;
        try {
            warmServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            warmServer.createContext("/warmup", exchange -> {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            warmServer.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "http-client-warmup-server");
                t.setDaemon(true);
                return t;
            }));
            warmServer.start();

            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + warmServer.getAddress().getPort() + "/warmup"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
            log.debug("HttpClient warmup termine avec succes.");
        } catch (Exception e) {
            log.debug("HttpClient warmup ignore (best-effort, sans impact) : {}", e.getClass().getSimpleName());
        } finally {
            if (warmServer != null) {
                warmServer.stop(0);
            }
        }
    }

    @Override
    public ScenarioExecutionOutcome execute(List<StepExecutionSpec> steps, String applicationBaseUrl,
            ApplicationAuthSpec auth, LoadTestSpec loadSpec, RunningExecutionHandle handle,
            List<Map<String, String>> vuVariableRows) {
        return execute(steps, applicationBaseUrl, auth, loadSpec, handle, VariableSources.of(vuVariableRows).withSteps(steps));
    }

    @Override
    public ScenarioExecutionOutcome execute(List<StepExecutionSpec> steps, String applicationBaseUrl,
            ApplicationAuthSpec auth, LoadTestSpec loadSpec, RunningExecutionHandle handle, VariableSources sources,
            com.loadpilot.backend.service.http.NetworkProfile networkProfile) {
        // Proxy de l'application (voir NetworkProfile) : clients de chaque VU, connexion et reconnexion.
        com.loadpilot.backend.service.http.NetworkProfile network = networkProfile == null
                ? com.loadpilot.backend.service.http.NetworkProfile.inherit() : networkProfile;
        // Authentification de l'Application resolue UNE fois pour toute
        // l'Execution (une seule connexion pour LOGIN_TOKEN, jeton partage
        // par les VUs). Echec -> aucune etape jouee, cause explicite.
        ResolvedAuth resolvedAuth;
        try {
            resolvedAuth = authenticator.authenticate(auth, applicationBaseUrl, timeoutSeconds, network);
        } catch (ApplicationAuthenticationException e) {
            return new ScenarioExecutionOutcome(List.of(), ExecutionStatus.FAILED, "Authentification : " + e.getMessage());
        }

        // Detail des requetes : secrets d'authentification connus des maintenant, toujours masques.
        RequestDetailRecorder recorder = handle.detailRecorder();
        if (recorder != null && recorder.enabled()) {
            List<String> secrets = new ArrayList<>(StepTestRunner.configuredSecrets(auth));
            secrets.addAll(StepTestRunner.resolvedSecrets(resolvedAuth));
            recorder.baseSecrets(secrets);
        }

        int virtualUsers = Math.max(1, loadSpec.virtualUsers());
        // Etalement reel du ramp-up : chaque VU demarre staggerMs apres le
        // precedent, jamais tous instantanement (voir rapport P0-A section 8).
        long staggerMs = (virtualUsers > 1 && loadSpec.rampUpSeconds() > 0)
                ? (loadSpec.rampUpSeconds() * 1000L) / virtualUsers
                : 0L;
        // Duree en temps ACTIF : le temps passe en pause (voir RunningExecutionHandle) n'est pas
        // decompte - un test de 20 s travaille vraiment 20 s, meme avec une pause au milieu.
        ActiveDeadline deadline = loadSpec.durationSeconds() != null
                ? new ActiveDeadline(System.nanoTime(), loadSpec.durationSeconds() * 1_000_000_000L, handle)
                : null;
        // Master prompt final (Lot A) - UN SEUL PacingGate partage par tous
        // les VUs de cette Execution (jamais un par VU, sinon le debit reel
        // serait targetRps * virtualUsers) ; null = aucun pacing configure,
        // comportement historique inchange.
        PacingGate pacingGate = loadSpec.targetRps() != null ? new PacingGate(loadSpec.targetRps()) : null;
        handle.startLiveStats(steps);
        // Correction L5 : jeton partage par tous les VUs, renouvele sur un 401 en « Connexion automatique ».
        RenewableApplicationAuth sharedAuth = new RenewableApplicationAuth(auth, resolvedAuth, applicationBaseUrl, timeoutSeconds,
                authenticator, recorder, network);

        // Resultats de chaque VU tenus ICI, pas dans son Future : un Future annule (annulation manuelle,
        // frein, pause trop longue, limite memoire - Future.cancel(true) interrompt les requetes en vol)
        // leve toujours CancellationException sur get(), et les resultats deja obtenus etaient perdus.
        List<VirtualUserRun> runs = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        // Ecriture au fil du test (ResultStream) : chaque resultat part vers le tampon d'ecriture au lieu
        // de rester dans la liste du VU. Sans flux (tests du moteur), comportement d'avant.
        ResultStream stream = handle.resultStream();
        for (int vu = 0; vu < virtualUsers; vu++) {
            long startDelayMs = vu * staggerMs;
            // Contexte de variables PROPRE a ce VU (jamais partage entre threads) : captures
            // videes a chaque iteration, ligne CSV attribuee selon la distribution du scenario
            // (une ligne par VU, ou une nouvelle ligne par iteration via le compteur partage
            // thread-safe de VariableSources), variables du scenario, ${authToken}.
            VariableContext variables = new VariableContext(sources.scenarioVariables(), resolvedAuth.token());
            int virtualUser = vu;
            VirtualUserRun run = new VirtualUserRun();
            runs.add(run);
            java.util.function.Consumer<StepOutcome> sink = stream != null ? stream::accept : run.results::add;
            Future<?> future = vuExecutor.submit(() -> {
                run.started.set(true);
                try {
                    runVirtualUser(steps, applicationBaseUrl, sharedAuth, startDelayMs, deadline, loadSpec, handle,
                            variables, sources, virtualUser, pacingGate, sink, network);
                } finally {
                    run.finished.countDown();
                }
            });
            handle.registerFuture(future);
            futures.add(future);
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (java.util.concurrent.CancellationException e) {
                // Annulation : ses resultats deja obtenus sont recuperes ci-dessous.
                log.debug("Utilisateur virtuel annule avant completion.");
            } catch (ExecutionException e) {
                log.warn("Utilisateur virtuel termine en erreur inattendue : {}", e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Apres une annulation, get() rend la main sans attendre la fin reelle du VU : on attend qu'il
        // soit vraiment arrete (interrompu, c'est immediat) pour que sa liste soit complete. Attente
        // bornee : un VU qui ne s'arreterait pas ne bloque jamais la finalisation (sa liste, protegee,
        // est alors lue telle quelle). Un VU annule avant d'avoir demarre n'a rien a attendre.
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(STOP_WAIT_SECONDS);
        List<StepOutcome> allOutcomes = new ArrayList<>();
        for (VirtualUserRun run : runs) {
            if (run.started.get()) {
                try {
                    long left = waitUntil - System.nanoTime();
                    if (left <= 0 || !run.finished.await(left, TimeUnit.NANOSECONDS)) {
                        log.warn("Utilisateur virtuel non arrete apres {} s : resultats lus en l'etat.", STOP_WAIT_SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (run.results) {
                allOutcomes.addAll(run.results);
            }
        }

        // Resultats deja confies au flux d'ecriture : seuls ses totaux sont rendus (la liste reste vide).
        ResultSummary summary = stream != null ? stream.summary() : ResultSummary.of(allOutcomes);
        boolean anyFailure = summary.failures() > 0;
        ExecutionStatus status = (summary.total() > 0 && !anyFailure) ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED;
        String errorMessage = anyFailure
                ? (summary.firstFailureError() != null ? summary.firstFailureError() : "Echec inconnu")
                : (summary.total() == 0 ? "Aucune requete n'a ete executee." : null);

        return new ScenarioExecutionOutcome(allOutcomes, status, errorMessage, summary);
    }

    /**
     * Boucle reelle d'UN utilisateur virtuel : demarre apres son delai de
     * ramp-up, puis enchaine des iterations jusqu'a la premiere condition
     * d'arret atteinte (dans cet ordre de verification, avant chaque
     * iteration) : annulation demandee > date limite de duree atteinte >
     * nombre d'iterations demande atteint > (par defaut, sans duree ni
     * iterations configurees) exactement UNE iteration - reproduit alors
     * exactement le comportement historique a une seule passe.
     */
    private void runVirtualUser(List<StepExecutionSpec> steps, String applicationBaseUrl,
            RenewableApplicationAuth sharedAuth, long startDelayMs, ActiveDeadline deadline, LoadTestSpec loadSpec, RunningExecutionHandle handle,
            VariableContext variables, VariableSources sources, int virtualUser, PacingGate pacingGate,
            java.util.function.Consumer<StepOutcome> results, com.loadpilot.backend.service.http.NetworkProfile network) {
        if (!handle.sleepActive(startDelayMs) || handle.isCancelled()) {
            return;
        }

        // HTTP_1_1 fixe explicitement (P0-A, section 21 - performance du
        // moteur) : sans cette ligne, java.net.http.HttpClient tente par
        // defaut une negociation HTTP/2 sur CHAQUE nouvelle connexion. Contre
        // une cible qui ne maintient pas la connexion ouverte entre deux
        // requetes (keep-alive absent ou desactive - constat REEL mesure
        // pendant cette phase avec le serveur JDK de test), cette tentative
        // ajoute jusqu'a ~1s de latence PAR REQUETE, ce qui fausserait
        // gravement tout test de charge reel. La plupart des cibles HTTP
        // reelles (dont banking-test-api) parlent HTTP/1.1.
        //
        // P1-Q Etape B - DEUX clients possibles (NORMAL et NEVER) :
        // HttpClient fixe sa politique de redirection a la CONSTRUCTION,
        // jamais par requete - un Step avec followRedirects=false utilise le
        // second client, tous les autres (null ou true) le premier
        // (comportement global historique inchange par defaut). Le second
        // n'est construit qu'a la premiere etape qui en a besoin : chaque
        // client demarre un thread systeme (selecteur) et ses descripteurs.
        //
        // Cookies de session : UN pot par VU (jamais partage, sinon les sessions des VUs se
        // melangeraient), commun a ses deux clients pour qu'une redirection suivie ou non ne
        // perde pas la session. Vide au debut de chaque iteration (voir runIterations).
        VirtualUserCookieJar cookies = newCookieJar();
        VirtualUserClients clients = new VirtualUserClients(cookies, network);

        // Suivi en direct (compteurs en memoire, aucune ecriture en base) : VU actif jusqu'a sa sortie.
        LiveStats live = handle.liveStats();
        if (live != null) live.virtualUserStarted();
        handle.virtualUserStarted(virtualUser);
        try {
            runIterations(steps, applicationBaseUrl, sharedAuth, deadline, loadSpec, handle, variables, sources,
                    virtualUser, pacingGate, clients, cookies, results, live);
        } finally {
            if (live != null) live.virtualUserFinished();
            handle.virtualUserFinished(virtualUser);
            // Apres le dernier results.accept (fait par CE thread, dans la boucle), avant le signal de fin
            // du VU (run.finished, dans execute) : aucune requete de ce VU n'est plus en cours d'envoi.
            clients.shutdownNow();
        }
    }

    /**
     * Clients HTTP d'UN utilisateur virtuel, utilises par son seul thread (jamais partages) : le
     * client qui suit les redirections, cree tout de suite, et celui qui ne les suit pas, cree a la
     * demande. Fermes par shutdownNow() a la fin du VU : sinon leur thread de selecteur, leurs
     * descripteurs et leurs connexions restent ouverts jusqu'au passage du GC.
     */
    private final class VirtualUserClients {
        private final VirtualUserCookieJar cookies;
        /** Proxy de l'application testee (voir NetworkProfile), commun aux deux clients. */
        private final com.loadpilot.backend.service.http.NetworkProfile network;
        private final HttpClient following;
        private HttpClient notFollowing;

        VirtualUserClients(VirtualUserCookieJar cookies, com.loadpilot.backend.service.http.NetworkProfile network) {
            this.network = network;
            this.cookies = cookies;
            this.following = build(HttpClient.Redirect.NORMAL);
        }

        HttpClient forStep(StepExecutionSpec step) {
            if (!Boolean.FALSE.equals(step.followRedirects())) return following;
            if (notFollowing == null) notFollowing = build(HttpClient.Redirect.NEVER);
            return notFollowing;
        }

        private HttpClient build(HttpClient.Redirect redirect) {
            return network.configure(HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .followRedirects(redirect)
                    .cookieHandler(cookies))
                    .build();
        }

        /**
         * shutdownNow, pas close() : close() attend la fin des echanges encore actifs - apres une
         * annulation en plein appel, jusqu'au delai d'attente de la requete. Ici, plus aucune reponse
         * n'est attendue. Jamais d'exception : la fin du VU et ses resultats passent avant.
         */
        void shutdownNow() {
            HttpClients.shutdownQuietly(following);
            HttpClients.shutdownQuietly(notFollowing);
        }
    }

    private void runIterations(List<StepExecutionSpec> steps, String applicationBaseUrl, RenewableApplicationAuth sharedAuth,
            ActiveDeadline deadline, LoadTestSpec loadSpec, RunningExecutionHandle handle, VariableContext variables,
            VariableSources sources, int virtualUser, PacingGate pacingGate, VirtualUserClients clients,
            VirtualUserCookieJar cookies, java.util.function.Consumer<StepOutcome> results, LiveStats live) {
        int iteration = 0;
        ThrottlePolicy throttle = loadSpec.throttle();
        RetryAfterSlot retryAfter = new RetryAfterSlot();
        while (true) {
            if (handle.isCancelled()) break;
            // Une pause en cours bloque ici avant toute nouvelle iteration.
            if (!handle.awaitIfPaused()) break;
            if (deadline != null && deadline.reached()) break;
            if (loadSpec.iterations() != null && iteration >= loadSpec.iterations()) break;
            if (deadline == null && loadSpec.iterations() == null && iteration >= 1) break;

            // Une iteration en echec n'arrete PAS les iterations suivantes de
            // CE VU (seule l'iteration courante s'arrete au premier echec,
            // via ce break) - voir politique de statut global en tete de
            // classe. La garde de sortie de boucle du haut se charge deja du
            // mode "1 passe" par defaut au tour suivant.
            // Nouvelle iteration : captures de l'iteration precedente oubliees, ligne CSV de
            // cette iteration (meme ligne a chaque iteration en distribution "par VU").
            variables.startIteration(sources.rowFor(virtualUser));
            // Meme regle pour les cookies automatiques : chaque iteration repart d'une session vierge.
            cookies.clear();
            int throttleRetries = 0;
            for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                StepExecutionSpec step = steps.get(stepIndex);
                if (handle.isCancelled()) break;
                // Master prompt final (Lot A) - un seul debit cible pour
                // TOUTE l'Execution (voir execute()) : chaque requete HTTP,
                // de n'importe quel VU, reserve son slot ici avant d'etre
                // envoyee. Sortie immediate si l'attente est interrompue par
                // une annulation reelle (jamais un blocage infini : la duree
                // d'attente est toujours finie, voir PacingGate).
                if (pacingGate != null && !pacingGate.acquire()) break;
                // Pause : aucune nouvelle requete n'est envoyee tant qu'elle dure (les requetes
                // deja en vol recoivent leur reponse normalement).
                if (!handle.awaitIfPaused()) break;

                StepOutcome outcome = executeStepWithRenewal(clients, step, stepIndex + 1, applicationBaseUrl, sharedAuth, variables,
                        sources, virtualUser, iteration, handle.detailRecorder(), retryAfter);
                // Requete coupee par l'annulation (aucune reponse recue) : ni resultat ni echec - c'est
                // l'arret demande, pas une erreur de l'application testee.
                if (handle.isCancelled() && outcome.httpStatus() == null) break;
                results.accept(outcome);
                boolean throttled = throttle.applies(outcome);
                if (live != null) live.record(stepIndex, outcome, throttled);
                // 429 respecte : CE VU attend le delai demande (Retry-After, ou delai par defaut), puis
                // rejoue la meme etape, session et captures conservees. Attente en temps actif (pause
                // non decomptee), interrompue par une annulation, jamais au-dela de la fin du test.
                if (throttled && throttleRetries < throttle.maxRetries()) {
                    throttleRetries++;
                    long waitMs = throttle.delayMs(retryAfter.value, Instant.now());
                    if (deadline != null) waitMs = Math.min(waitMs, deadline.remainingMs());
                    if (!handle.sleepActive(waitMs) || handle.isCancelled()) break;
                    if (deadline != null && deadline.reached()) break;
                    stepIndex--;
                    continue;
                }
                throttleRetries = 0;
                // P1-Q Etape B - pause specifique a CETTE etape, en plus du
                // think time de Scenario ci-dessous (jamais a la place) -
                // jamais appliquee apres une annulation ni si non configuree.
                if (!handle.isCancelled() && step.thinkTimeMs() != null && step.thinkTimeMs() > 0
                        && !handle.sleepActive(ThinkTime.pick(step.thinkTimeMs(), loadSpec.thinkTimeVariationPercent()))) {
                    break;
                }
                if (!outcome.success()) {
                    break;
                }
            }
            iteration++;
            if (live != null) live.iterationCompleted();
            // Think time uniquement APRES une iteration, jamais apres une
            // annulation, jamais applique si nul.
            if (!handle.isCancelled() && loadSpec.thinkTimeMs() > 0
                    && !handle.sleepActive(ThinkTime.pick(loadSpec.thinkTimeMs(), loadSpec.thinkTimeVariationPercent()))) {
                break;
            }
        }
    }

    /** Attente maximale de l'arret reel des VU apres une annulation (secondes). */
    static final long STOP_WAIT_SECONDS = 5;

    /**
     * Un VU : ses resultats (liste propre, synchronisee - verrou jamais dispute pendant le test, seul
     * son VU y ecrit) et la fin reelle de sa tache.
     */
    private static final class VirtualUserRun {
        final List<StepOutcome> results = java.util.Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
    }

    /** En-tete Retry-After du dernier 429 recu par CE VU (jamais partage entre VU). */
    private static final class RetryAfterSlot {
        String value;
    }

    /** Pot de cookies d'un nouveau VU (package-private et redefinissable : tests de non-retention memoire). */
    VirtualUserCookieJar newCookieJar() {
        return new VirtualUserCookieJar();
    }

    /** Fin de la duree configuree, mesuree en temps actif (pauses exclues). */
    private record ActiveDeadline(long startNanos, long durationNanos, RunningExecutionHandle handle) {
        boolean reached() {
            return System.nanoTime() - startNanos - handle.pausedNanos() >= durationNanos;
        }

        /** Arrondi vers le HAUT : une attente de cette duree se termine a la fin du test, jamais juste avant. */
        long remainingMs() {
            long nanos = durationNanos - (System.nanoTime() - startNanos - handle.pausedNanos());
            return nanos <= 0 ? 0L : (nanos + 999_999L) / 1_000_000L;
        }
    }

    /**
     * Correction L5 - envoie l'etape avec le jeton en vigueur. En « Connexion automatique », une
     * reponse 401 qui fait echouer l'etape, sur une requete qui portait le jeton de l'application,
     * declenche UN renouvellement (partage entre VUs, voir RenewableApplicationAuth) puis UN seul
     * rejeu avec le nouveau jeton ; son resultat remplace celui du 401. Reconnexion impossible :
     * l'echec du 401 remonte tel quel, avec la raison. Autres modes : comportement inchange.
     */
    private StepOutcome executeStepWithRenewal(VirtualUserClients client, StepExecutionSpec step, int stepNumber, String applicationBaseUrl,
            RenewableApplicationAuth sharedAuth, VariableContext variables, VariableSources sources, int virtualUser, int iteration,
            RequestDetailRecorder recorder, RetryAfterSlot retryAfter) {
        RenewableApplicationAuth.Current used = sharedAuth.current();
        variables.updateAuthToken(used.resolved().token());
        StepOutcome outcome = executeStep(client, step, stepNumber, applicationBaseUrl, used.resolved(), variables, sources,
                virtualUser, iteration, recorder, retryAfter);
        if (!sharedAuth.renewable() || outcome.success() || !Integer.valueOf(401).equals(outcome.httpStatus())
                || !usesApplicationToken(step, used.resolved()) || Thread.currentThread().isInterrupted()) {
            return outcome;
        }
        RenewableApplicationAuth.Current fresh = sharedAuth.renewAfter(used);
        if (fresh == null) {
            return new StepOutcome(outcome.stepId(), outcome.httpStatus(), outcome.responseTimeMs(), false,
                    outcome.error() + " ; reconnexion a l'application impossible", outcome.timestamp(), outcome.virtualUser(),
                    outcome.iteration(), outcome.requestUrl(), outcome.detail());
        }
        variables.updateAuthToken(fresh.resolved().token());
        return executeStep(client, step, stepNumber, applicationBaseUrl, fresh.resolved(), variables, sources, virtualUser,
                iteration, recorder, retryAfter);
    }

    /**
     * La requete portait-elle le jeton de l'application ? Oui si l'etape utilise ${authToken}, ou si
     * un en-tete d'authentification de l'application a ete ajoute (l'etape ne le definit pas elle-meme).
     * Une etape qui envoie son propre jeton (ex. capture ${userToken}) n'est jamais concernee.
     */
    private boolean usesApplicationToken(StepExecutionSpec step, ResolvedAuth auth) {
        String marker = "${" + ResolvedAuth.TOKEN_VARIABLE + "}";
        for (String template : new String[] {step.url(), step.headers(), step.body(), step.pathParams()}) {
            if (template != null && template.contains(marker)) return true;
        }
        java.util.Set<String> stepHeaders = new java.util.HashSet<>();
        parseHeaders(step.headers()).keySet().forEach(n -> stepHeaders.add(n.toLowerCase(java.util.Locale.ROOT)));
        return auth.headers().keySet().stream().anyMatch(n -> !stepHeaders.contains(n.toLowerCase(java.util.Locale.ROOT)));
    }

    private StepOutcome executeStep(VirtualUserClients clients, StepExecutionSpec step, int stepNumber, String applicationBaseUrl,
            ResolvedAuth resolvedAuth, VariableContext variables, VariableSources sources, int virtualUser, int iteration,
            RequestDetailRecorder recorder, RetryAfterSlot retryAfter) {
        HttpClient client = clients.forStep(step);
        // Requete reelle construite depuis les gabarits de l'etape (voir RequestBuilder) :
        // variables de chemin / query encodees, priorite capture > CSV > scenario > authToken.
        RequestBuilder.BuiltRequest built = RequestBuilder.build(step, applicationBaseUrl, variables::get, objectMapper);
        String requestUrl = traceableUrl(built.url());
        long effectiveTimeoutSeconds = step.timeoutSeconds() != null ? step.timeoutSeconds() : timeoutSeconds;
        // Detail des requetes (echecs + echantillon) : voir RequestDetailRecorder. Desactive (NONE ou
        // aucune politique), le comportement historique est strictement inchange.
        boolean detailOn = recorder != null && recorder.enabled();
        DetailContext detail = detailOn ? new DetailContext(recorder, step, stepNumber, iteration, variables, resolvedAuth) : null;
        if (!built.missing().isEmpty()) {
            // Jamais de requete envoyee avec un ${...} brut : echec explicite avant l'envoi.
            StepOutcome outcome = new StepOutcome(step.stepId(), null, 0, false,
                    RequestBuilder.missingMessage(built.missing(), stepNumber, sources.captureStepByVariable()),
                    Instant.now(), virtualUser, iteration, requestUrl);
            return detailOn ? detail.attach(outcome, built.url(), built.headers(), built.body(), null, null, null, null) : outcome;
        }
        String url = resolvedAuth.applyToUrl(built.url());
        long startedAt = System.nanoTime();

        boolean captureConfigured = step.captureVariableName() != null && !step.captureVariableName().isBlank()
                && step.captureJsonPath() != null && !step.captureJsonPath().isBlank();
        boolean hasAssertion = step.assertionBodyContains() != null && !step.assertionBodyContains().isBlank();

        HttpRequest request = null;
        try {
            request = buildRequest(step, url, built, resolvedAuth, effectiveTimeoutSeconds, clients.network);
            // Capture depuis un en-tete : le corps n'est pas necessaire pour elle.
            boolean readBody = hasAssertion || step.hasJsonAssertion() || (captureConfigured && !step.capturesFromHeader());
            int statusCode;
            java.net.http.HttpHeaders responseHeaders;
            String body = null;
            CappedBody keptBody = null;
            if (readBody) {
                // Assertion de contenu ou capture : corps complet necessaire.
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                statusCode = response.statusCode();
                responseHeaders = response.headers();
                body = response.body();
                if (detailOn) keptBody = CappedBody.of(body, RequestDetailRecorder.BODY_LIMIT_BYTES);
            } else if (detailOn) {
                // Detail actif : seuls les 10 premiers Ko du corps sont gardes en memoire.
                HttpResponse<CappedBody> response = client.send(request, CappedBody.handler(RequestDetailRecorder.BODY_LIMIT_BYTES));
                statusCode = response.statusCode();
                responseHeaders = response.headers();
                keptBody = response.body();
            } else {
                // P0-B : corps jamais lu ni stocke par defaut (discarding).
                HttpResponse<String> response = client.send(request, discardingAsString());
                statusCode = response.statusCode();
                responseHeaders = response.headers();
            }
            long elapsed = elapsedMs(startedAt);
            // 429 : delai demande par la cible, lu ici (seul endroit ou les en-tetes sont disponibles).
            if (retryAfter != null) {
                retryAfter.value = statusCode == ThrottlePolicy.TOO_MANY_REQUESTS
                        ? responseHeaders.firstValue("Retry-After").orElse(null) : null;
            }
            boolean statusOk = evaluateSuccess(step, statusCode);
            boolean assertionOk = !hasAssertion || body.contains(step.assertionBodyContains());
            // Controles optionnels (temps de reponse maximal, assertion JSON) : en plus des precedents.
            String jsonProblem = statusOk && assertionOk ? StepChecks.jsonAssertionProblem(objectMapper, body,
                    step.assertionJsonPath(), step.assertionJsonOperator(), step.assertionJsonValue()) : null;
            String timeProblem = StepChecks.responseTimeProblem(step.maxResponseTimeMs(), elapsed);
            boolean success = statusOk && assertionOk && jsonProblem == null && timeProblem == null;
            String error = !statusOk
                    ? "Reponse HTTP " + statusCode + " (attendu " + describeExpectation(step) + ")"
                    : !assertionOk
                    ? missingContentMessage(step.assertionBodyContains())
                    : jsonProblem != null ? jsonProblem
                    : timeProblem;
            // Capture apres evaluation, quel que soit le resultat de l'assertion ; propre a ce VU
            // et a l'iteration en cours (VariableContext, videe au debut de chaque iteration).
            String captured = null;
            // Capture HTML d'un element dont le nom ressemble a un jeton (csrf, token...) : valeur masquee dans le detail.
            boolean sensitiveHtmlCapture = false;
            if (captureConfigured) {
                if (step.capturesFromHtml()) {
                    HtmlExtractor.Match match = HtmlExtractor.find(body, step.captureJsonPath());
                    captured = match.value();
                    sensitiveHtmlCapture = TestRunMasking.isSensitiveHtmlCapture(step.captureJsonPath(), match.elementName());
                } else {
                    captured = step.capturesFromHeader()
                            ? ResponseHeaderExtractor.extract(responseHeaders, step.captureJsonPath())
                            : JsonPathExtractor.extract(objectMapper, body, step.captureJsonPath());
                }
                if (captured != null) {
                    variables.capture(step.captureVariableName().trim(), captured);
                }
            }
            StepOutcome outcome = new StepOutcome(step.stepId(), statusCode, elapsed, success, error, Instant.now(),
                    virtualUser, iteration, requestUrl);
            if (!detailOn) return outcome;
            RequestDetail.Checks checks = new RequestDetail.Checks(describeExpectation(step), statusOk,
                    hasAssertion ? step.assertionBodyContains() : null, hasAssertion ? assertionOk : null,
                    captureConfigured ? step.captureVariableName().trim() : null, captureConfigured ? step.captureJsonPath() : null,
                    sensitiveHtmlCapture && captured != null ? TestRunMasking.MASK : captured, captureConfigured ? captured != null : null);
            return detail.attach(outcome, url, headerMap(request.headers()), built.body(), statusCode, headerMap(responseHeaders),
                    keptBody, checks);
        } catch (HttpTimeoutException e) {
            StepOutcome outcome = new StepOutcome(step.stepId(), null, elapsedMs(startedAt), false,
                    "Delai d'attente depasse (" + effectiveTimeoutSeconds + "s)", Instant.now(), virtualUser, iteration, requestUrl);
            return detailOn ? detail.attach(outcome, url, sentHeaders(request, built), built.body(), null, null, null, null) : outcome;
        } catch (IOException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.debug("Etape '{}' echouee (technique) : {}", step.name(), e.getClass().getSimpleName());
            StepOutcome outcome = new StepOutcome(step.stepId(), null, elapsedMs(startedAt), false, message, Instant.now(),
                    virtualUser, iteration, requestUrl);
            return detailOn ? detail.attach(outcome, url, sentHeaders(request, built), built.body(), null, null, null, null) : outcome;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StepOutcome(step.stepId(), null, elapsedMs(startedAt), false, "Execution interrompue.", Instant.now(),
                    virtualUser, iteration, requestUrl);
        } catch (IllegalArgumentException e) {
            StepOutcome outcome = new StepOutcome(step.stepId(), null, 0, false, "URL invalide : " + e.getClass().getSimpleName(),
                    Instant.now(), virtualUser, iteration, requestUrl);
            return detailOn ? detail.attach(outcome, url, built.headers(), built.body(), null, null, null, null) : outcome;
        }
    }

    private static Map<String, String> headerMap(java.net.http.HttpHeaders headers) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        headers.map().forEach((name, values) -> map.put(name, String.join(", ", values)));
        return map;
    }

    private static Map<String, String> sentHeaders(HttpRequest request, RequestBuilder.BuiltRequest built) {
        return request != null ? headerMap(request.headers()) : built.headers();
    }

    /**
     * Construction du detail d'UNE requete, seulement si la politique le retient (echec dans les
     * quotas, ou echantillon) : secrets masques (authentification, variables au nom sensible,
     * en-tetes sensibles, champs JSON sensibles), corps limites a 10 Ko.
     */
    private record DetailContext(RequestDetailRecorder recorder, StepExecutionSpec step, int stepNumber, int iteration,
            VariableContext variables, ResolvedAuth resolvedAuth) {

        StepOutcome attach(StepOutcome outcome, String url, Map<String, String> requestHeaders, String requestBody,
                Integer status, Map<String, String> responseHeaders, CappedBody responseBody, RequestDetail.Checks checks) {
            String failureType = outcome.success() ? null : RequestDetailRecorder.failureType(outcome.httpStatus(), outcome.error());
            boolean wanted = outcome.success() ? recorder.wantsSample(iteration) : recorder.wantsFailure(stepNumber, failureType);
            if (!wanted) return outcome;

            java.util.Set<String> secrets = new java.util.LinkedHashSet<>(recorder.baseSecrets());
            secrets.addAll(RequestDetailRecorder.sensitiveValues(variables.snapshot()));
            CappedBody sentBody = CappedBody.of(requestBody, RequestDetailRecorder.BODY_LIMIT_BYTES);
            String maskedUrl = TestRunMasking.maskUrl(url, resolvedAuth.queryParams().keySet(), secrets);
            RequestDetail detail = new RequestDetail(
                    outcome.success() ? RequestDetail.Kind.SAMPLE : RequestDetail.Kind.FAILURE,
                    step.method().name(),
                    maskedUrl != null && maskedUrl.length() > 2048 ? maskedUrl.substring(0, 2048) : maskedUrl,
                    requestHeaders == null ? Map.of() : TestRunMasking.maskHeaders(requestHeaders, sensitiveHeaderNames(requestHeaders), secrets),
                    TestRunMasking.maskBody(sentBody.text(), secrets),
                    sentBody.truncated(),
                    status,
                    responseHeaders == null ? null : TestRunMasking.maskHeaders(responseHeaders, sensitiveHeaderNames(responseHeaders), secrets),
                    responseBody == null ? null : TestRunMasking.maskBody(responseBody.text(), secrets),
                    responseBody != null && responseBody.truncated(),
                    responseBody == null ? null : responseBody.totalBytes(),
                    checks == null ? null : maskChecks(checks, secrets, step.capturesFromHeader()));
            boolean accepted = outcome.success()
                    ? recorder.acceptSample(detail.approxBytes())
                    : recorder.acceptFailure(stepNumber, failureType, detail.approxBytes());
            return accepted ? outcome.withDetail(detail) : outcome;
        }

        /** En-tetes d'authentification de l'application et en-tetes au nom sensible (X-Auth-Token…), en minuscules. */
        private java.util.Set<String> sensitiveHeaderNames(Map<String, String> headers) {
            java.util.Set<String> names = new java.util.HashSet<>();
            resolvedAuth.headers().keySet().forEach(n -> names.add(n.toLowerCase(java.util.Locale.ROOT)));
            headers.keySet().forEach(n -> {
                if (TestRunMasking.isSensitiveName(n)) names.add(n.toLowerCase(java.util.Locale.ROOT));
            });
            return names;
        }

        private static RequestDetail.Checks maskChecks(RequestDetail.Checks c, java.util.Set<String> secrets, boolean fromHeader) {
            String value = c.captureValue();
            boolean sensitive = value != null && (TestRunMasking.isSensitiveName(c.captureName())
                    || TestRunMasking.isSensitiveName(c.capturePath()) || secrets.contains(value)
                    || (fromHeader && TestRunMasking.isSensitiveHeaderCapture(c.capturePath())));
            return new RequestDetail.Checks(c.expectedStatus(), c.statusOk(),
                    c.assertionText() == null ? null : TestRunMasking.maskSecrets(c.assertionText(), secrets), c.assertionOk(),
                    c.captureName(), c.capturePath(),
                    sensitive ? TestRunMasking.MASK : TestRunMasking.maskSecrets(value, secrets), c.captureFound());
        }
    }

    /** URL finale conservee dans les resultats : parametres sensibles masques, 2048 caracteres max. */
    private static String traceableUrl(String url) {
        String masked = TestRunMasking.maskUrl(url, java.util.Set.of(), java.util.Set.of());
        return masked != null && masked.length() > 2048 ? masked.substring(0, 2048) : masked;
    }

    /** Meme garantie que BodyHandlers.discarding() (rien bufferise) mais
     * type Void->String pour partager la meme signature client.send(...)
     * que le cas "assertion" (readBody=true) ci-dessus - jamais de corps
     * lu ici, body() renvoie simplement null. */
    private HttpResponse.BodyHandler<String> discardingAsString() {
        return responseInfo -> HttpResponse.BodySubscribers.replacing(null);
    }

    private HttpRequest buildRequest(StepExecutionSpec step, String url, RequestBuilder.BuiltRequest built,
            ResolvedAuth resolvedAuth, long effectiveTimeoutSeconds, com.loadpilot.backend.service.http.NetworkProfile network) {
        String body = built.body();
        HttpRequest.BodyPublisher bodyPublisher = (body != null && !body.isBlank())
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(effectiveTimeoutSeconds));

        switch (step.method()) {
            case GET -> builder.GET();
            case POST -> builder.POST(bodyPublisher);
            case PUT -> builder.PUT(bodyPublisher);
            case PATCH -> builder.method("PATCH", bodyPublisher);
            case DELETE -> builder.method("DELETE", bodyPublisher);
        }

        built.headers().forEach(builder::header);
        resolvedAuth.applyHeaders(builder, built.headers().keySet());
        // Identifiant du proxy (de l'application, ou du serveur), seulement si la requete passe par lui.
        network.applyTo(builder, URI.create(url), proxyAuthorization);
        return builder.build();
    }

    private Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            // Jamais logger le contenu des headers (potentiellement sensible) -
            // uniquement le type d'erreur de parsing.
            log.debug("Headers de l'etape illisibles ({}), ignores.", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private boolean evaluateSuccess(StepExecutionSpec step, int actualStatus) {
        if (step.expectedStatus() != null) {
            return actualStatus == step.expectedStatus();
        }
        // Recommandation Phase 9 : sans expectedStatus explicite, 200-399 = succes.
        return actualStatus >= 200 && actualStatus < 400;
    }

    /**
     * Message d'echec d'une reponse au bon code HTTP mais sans le contenu attendu (meme libelle que
     * « Tester ») ; texte attendu tronque a 200 caracteres pour garder un message lisible.
     */
    public static String missingContentMessage(String expected) {
        String text = expected.length() > 200 ? expected.substring(0, 200) + "…" : expected;
        return "Contenu attendu introuvable : " + text;
    }

    private String describeExpectation(StepExecutionSpec step) {
        return step.expectedStatus() != null ? String.valueOf(step.expectedStatus()) : "2xx-3xx";
    }

    private long elapsedMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
