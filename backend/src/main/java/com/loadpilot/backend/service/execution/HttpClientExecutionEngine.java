package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.ExecutionStatus;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PostConstruct;
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
 *
 * DECOUPAGE : cette classe orchestre l'execution (authentification, lancement et attente des VU,
 * collecte des resultats, statut) et envoie chaque etape (executeStep : requete, controles, detail ;
 * executeStepWithRenewal : jeton renouvele sur un 401). Le reste est delegue :
 *   - VirtualUserLoop : boucle d'un VU (ramp-up, iterations, debit cible, pauses, think time) ;
 *   - VirtualUserClients : clients HTTP d'un VU ; ActiveDeadline : duree en temps actif ;
 *   - RetryAfterHandler : 429 Too Many Requests (Retry-After, rejeux) ;
 *   - ResponseCaptures : captures de valeurs (JSON, en-tete, HTML).
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
        // Boucle de chaque VU (voir VirtualUserLoop) ; l'envoi d'une etape reste ici (executeStepWithRenewal).
        VirtualUserLoop.Shared shared = new VirtualUserLoop.Shared(steps, loadSpec, handle, deadline, pacingGate, sources, network,
                timeoutSeconds, this::newCookieJar,
                (clients, step, stepNumber, variables, virtualUser, iteration, retryAfter) -> executeStepWithRenewal(clients, step,
                        stepNumber, applicationBaseUrl, sharedAuth, variables, sources, virtualUser, iteration, handle.detailRecorder(),
                        retryAfter));
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
            VirtualUserLoop loop = new VirtualUserLoop(shared, virtualUser, startDelayMs, variables, sink);
            Future<?> future = vuExecutor.submit(() -> {
                run.started.set(true);
                try {
                    loop.run();
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

    /** Pot de cookies d'un nouveau VU (package-private et redefinissable : tests de non-retention memoire). */
    VirtualUserCookieJar newCookieJar() {
        return new VirtualUserCookieJar();
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
            RequestDetailRecorder recorder, RetryAfterHandler retryAfter) {
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
            RequestDetailRecorder recorder, RetryAfterHandler retryAfter) {
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

        boolean captureConfigured = ResponseCaptures.configured(step);
        boolean hasAssertion = step.assertionBodyContains() != null && !step.assertionBodyContains().isBlank();

        HttpRequest request = null;
        try {
            request = buildRequest(step, url, built, resolvedAuth, effectiveTimeoutSeconds, clients.network());
            // Capture depuis un en-tete : le corps n'est pas necessaire pour elle.
            boolean readBody = hasAssertion || step.hasJsonAssertion() || ResponseCaptures.needsBody(step);
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
            if (retryAfter != null) retryAfter.recordResponse(statusCode, responseHeaders);
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
            // Capture HTML d'un element dont le nom ressemble a un jeton (csrf, token...) : valeur masquee dans le detail.
            ResponseCaptures.Captured capture = ResponseCaptures.extract(step, body, responseHeaders, objectMapper);
            ResponseCaptures.store(step, capture, variables);
            String captured = capture.value();
            boolean sensitiveHtmlCapture = capture.sensitive();
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
