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

/** Moteur de charge : un thread virtuel par utilisateur virtuel, qui rejoue les etapes du scenario avec java.net.http.HttpClient. */
@Component
public class HttpClientExecutionEngine implements ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(HttpClientExecutionEngine.class);

    private final long timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final ExecutorService vuExecutor;
    private final ApplicationAuthenticator authenticator;
    /** Identifiant du proxy d'entreprise. */
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

    /** Premier appel a HttpClient fait au demarrage, pour que le premier test ne paie pas l'initialisation du client. */
    @PostConstruct
    void scheduleHttpClientWarmup() {
        Thread.ofVirtual().name("http-client-warmup").start(this::warmUpHttpClient);
    }

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
        // Reglages reseau de l'application.
        com.loadpilot.backend.service.http.NetworkProfile network = networkProfile == null
                ? com.loadpilot.backend.service.http.NetworkProfile.inherit() : networkProfile;
        // Authentification resolue une fois pour toute l'execution ; en cas d'echec, aucune etape n'est jouee.
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

        // Mode stress : montee automatique, reglages de charge du scenario ignores ; null = mode manuel.
        StressRamp.Limits stress = loadSpec.stress();
        int virtualUsers = Math.max(1, loadSpec.virtualUsers());
        // Montee en charge : chaque utilisateur virtuel demarre staggerMs apres le precedent.
        long staggerMs = (stress == null && virtualUsers > 1 && loadSpec.rampUpSeconds() > 0)
                ? (loadSpec.rampUpSeconds() * 1000L) / virtualUsers
                : 0L;
        // Duree mesuree en temps actif (pauses exclues) ; mode stress : duree maximale d'un test.
        Integer durationSeconds = stress != null ? Integer.valueOf(stress.maxDurationSeconds()) : loadSpec.durationSeconds();
        ActiveDeadline deadline = durationSeconds != null
                ? new ActiveDeadline(System.nanoTime(), durationSeconds * 1_000_000_000L, handle)
                : null;
        // Pacing partage par tous les utilisateurs virtuels de l'execution ; null = aucun pacing.
        // Mode stress : debit reglable, pilote par la montee.
        PacingGate pacingGate = stress != null ? PacingGate.adjustable(StressSettings.BASELINE_RPS)
                : loadSpec.targetRps() != null ? new PacingGate(loadSpec.targetRps()) : null;
        LiveStats live = handle.startLiveStats(steps);
        // Jeton partage, renouvele sur un 401 en "Connexion automatique".
        RenewableApplicationAuth sharedAuth = new RenewableApplicationAuth(auth, resolvedAuth, applicationBaseUrl, timeoutSeconds,
                authenticator, recorder, network);

        // Resultats de chaque utilisateur virtuel, recuperables meme apres une annulation ; en mode stress,
        // des utilisateurs virtuels s'ajoutent pendant le test.
        List<VirtualUserRun> runs = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Future<?>> futures = new java.util.concurrent.CopyOnWriteArrayList<>();
        // Resultats ecrits au fil du test (ResultStream).
        ResultStream stream = handle.resultStream();
        VirtualUserLoop.Shared shared = new VirtualUserLoop.Shared(steps, loadSpec, handle, deadline, pacingGate, sources, network,
                timeoutSeconds, this::newCookieJar,
                (clients, step, stepNumber, variables, virtualUser, iteration, retryAfter) -> executeStepWithRenewal(clients, step,
                        stepNumber, applicationBaseUrl, sharedAuth, variables, sources, virtualUser, iteration, handle.detailRecorder(),
                        retryAfter));
        // Demarre l'utilisateur virtuel "virtualUser" apres "startDelayMs".
        java.util.function.BiConsumer<Integer, Long> startVirtualUser = (virtualUser, startDelayMs) -> {
            // Contexte de variables propre a cet utilisateur virtuel ; ligne CSV selon la distribution du scenario.
            VariableContext variables = new VariableContext(sources.scenarioVariables(), resolvedAuth.token());
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
        };
        if (stress != null) {
            runStress(stress, pacingGate, live, handle, startVirtualUser);
        } else {
            for (int vu = 0; vu < virtualUsers; vu++) {
                startVirtualUser.accept(vu, vu * staggerMs);
            }
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (java.util.concurrent.CancellationException e) {
                log.debug("Utilisateur virtuel annule avant completion.");
            } catch (ExecutionException e) {
                log.warn("Utilisateur virtuel termine en erreur inattendue : {}", e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Apres une annulation, attente (bornee) de l'arret reel de l'utilisateur virtuel.
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

        ResultSummary summary = stream != null ? stream.summary() : ResultSummary.of(allOutcomes);
        boolean anyFailure = summary.failures() > 0;
        ExecutionStatus status = (summary.total() > 0 && !anyFailure) ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED;
        String errorMessage = anyFailure
                ? (summary.firstFailureError() != null ? summary.firstFailureError() : "Echec inconnu")
                : (summary.total() == 0 ? "Aucune requete n'a ete executee." : null);

        return new ScenarioExecutionOutcome(allOutcomes, status, errorMessage, summary);
    }

    /**
     * Mode stress : demarre le reservoir, fait tourner la montee (un tick par seconde, dans ce thread) jusqu'a une limite
     * de LoadPilot ou l'annulation, puis demande l'arret : chaque utilisateur virtuel termine sa requete en cours.
     */
    private static void runStress(StressRamp.Limits limits, PacingGate gate, LiveStats live, RunningExecutionHandle handle,
            java.util.function.BiConsumer<Integer, Long> startVirtualUser) {
        java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
        StressRamp.Pool pool = new StressRamp.Pool() {
            @Override
            public int virtualUsers() {
                return created.get();
            }

            @Override
            public void add(int count) {
                for (int i = 0; i < count; i++) startVirtualUser.accept(created.getAndIncrement(), 0L);
            }
        };
        StressRamp ramp = new StressRamp(limits, gate, live, pool);
        handle.stressRamp(ramp);
        pool.add(Math.min(StressSettings.INITIAL_VIRTUAL_USERS, limits.maxVirtualUsers()));
        StressRamp.Limit limit = ramp.run(handle);
        if (limit != null) {
            log.info("Test de stress : montee terminee a la limite {} ({} VU, {} req/s offertes).", limit,
                    ramp.peakVirtualUsers(), ramp.peakOfferedRps());
        }
        handle.requestStop();
    }

    /** Attente maximale de l'arret reel des VU apres une annulation (secondes). */
    static final long STOP_WAIT_SECONDS = 5;

    /** Un utilisateur virtuel : ses resultats et sa tache. */
    private static final class VirtualUserRun {
        final List<StepOutcome> results = java.util.Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
    }

    /** Pot de cookies d'un nouvel utilisateur virtuel. */
    VirtualUserCookieJar newCookieJar() {
        return new VirtualUserCookieJar();
    }


    /** Envoie l'etape ; en "Connexion automatique", un 401 obtenu avec le jeton de l'application declenche un renouvellement puis un nouvel essai. */
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

    /** true si la requete portait le jeton de l'application (variable ${authToken} ou en-tete d'authentification ajoute). */
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
        // Requete construite a partir des gabarits de l'etape (priorite : capture > CSV > scenario > authToken).
        RequestBuilder.BuiltRequest built = RequestBuilder.build(step, applicationBaseUrl, variables::get, objectMapper);
        String requestUrl = traceableUrl(built.url());
        long effectiveTimeoutSeconds = step.timeoutSeconds() != null ? step.timeoutSeconds() : timeoutSeconds;
        boolean detailOn = recorder != null && recorder.enabled();
        DetailContext detail = detailOn ? new DetailContext(recorder, step, stepNumber, iteration, variables, resolvedAuth) : null;
        if (!built.missing().isEmpty()) {
            // Variable introuvable : echec avant l'envoi.
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
            boolean readBody = hasAssertion || step.hasJsonAssertion() || ResponseCaptures.needsBody(step);
            int statusCode;
            java.net.http.HttpHeaders responseHeaders;
            String body = null;
            CappedBody keptBody = null;
            if (readBody) {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                statusCode = response.statusCode();
                responseHeaders = response.headers();
                body = response.body();
                if (detailOn) keptBody = CappedBody.of(body, RequestDetailRecorder.BODY_LIMIT_BYTES);
            } else if (detailOn) {
                HttpResponse<CappedBody> response = client.send(request, CappedBody.handler(RequestDetailRecorder.BODY_LIMIT_BYTES));
                statusCode = response.statusCode();
                responseHeaders = response.headers();
                keptBody = response.body();
            } else {
                // Corps non lu.
                HttpResponse<String> response = client.send(request, discardingAsString());
                statusCode = response.statusCode();
                responseHeaders = response.headers();
            }
            long elapsed = elapsedMs(startedAt);
            // 429 : delai demande par la cible.
            if (retryAfter != null) retryAfter.recordResponse(statusCode, responseHeaders);
            boolean statusOk = evaluateSuccess(step, statusCode);
            boolean assertionOk = !hasAssertion || body.contains(step.assertionBodyContains());
            // Controles optionnels : temps de reponse maximal et assertion JSON.
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
            // Capture faite quel que soit le resultat de l'assertion ; valeur propre a cet utilisateur virtuel et a l'iteration.
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
                    "Delai d'attente depasse (" + effectiveTimeoutSeconds + "s)", Instant.now(), virtualUser, iteration, requestUrl,
                    null, com.loadpilot.backend.service.http.NetworkErrors.TIMEOUT);
            return detailOn ? detail.attach(outcome, url, sentHeaders(request, built), built.body(), null, null, null, null) : outcome;
        } catch (IOException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.debug("Etape '{}' echouee (technique) : {}", step.name(), e.getClass().getSimpleName());
            StepOutcome outcome = new StepOutcome(step.stepId(), null, elapsedMs(startedAt), false, message, Instant.now(),
                    virtualUser, iteration, requestUrl, null, com.loadpilot.backend.service.http.NetworkErrors.kind(e));
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

    /** Detail d'une requete conservee : secrets masques, corps limites. */
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

        /** En-tetes a masquer : authentification de l'application et noms sensibles, en minuscules. */
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

    /** URL enregistree dans les resultats : parametres sensibles masques, 2048 caracteres au plus. */
    private static String traceableUrl(String url) {
        String masked = TestRunMasking.maskUrl(url, java.util.Set.of(), java.util.Set.of());
        return masked != null && masked.length() > 2048 ? masked.substring(0, 2048) : masked;
    }

    /** Handler qui ne lit pas le corps ; body() renvoie null. */
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
            log.debug("Headers de l'etape illisibles ({}), ignores.", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private boolean evaluateSuccess(StepExecutionSpec step, int actualStatus) {
        if (step.expectedStatus() != null) {
            return actualStatus == step.expectedStatus();
        }
        // Sans expectedStatus, 200-399 = succes.
        return actualStatus >= 200 && actualStatus < 400;
    }

    /** Message d'echec quand le contenu attendu est absent (texte attendu tronque a 200 caracteres). */
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
