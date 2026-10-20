package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.dto.response.TestRunResponse;
import com.loadpilot.backend.dto.response.TestRunResponse.AssertionCheck;
import com.loadpilot.backend.dto.response.TestRunResponse.AuthResult;
import com.loadpilot.backend.dto.response.TestRunResponse.CaptureCheck;
import com.loadpilot.backend.dto.response.TestRunResponse.Checks;
import com.loadpilot.backend.dto.response.TestRunResponse.JsonCheck;
import com.loadpilot.backend.dto.response.TestRunResponse.ResponseTimeCheck;
import com.loadpilot.backend.dto.response.TestRunResponse.ReceivedResponse;
import com.loadpilot.backend.dto.response.TestRunResponse.SentRequest;
import com.loadpilot.backend.dto.response.TestRunResponse.StatusCheck;
import com.loadpilot.backend.dto.response.TestRunResponse.StepResult;
import com.loadpilot.backend.dto.response.TestRunResponse.UrlWarning;
import com.loadpilot.backend.enums.ApiKeyLocation;
import com.loadpilot.backend.service.auth.ApplicationAuthConfig;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.loadpilot.backend.service.http.NetworkErrors;
import com.loadpilot.backend.service.http.HttpClients;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Test de verification detaille d'un scenario (ou d'une seule etape) : 1 utilisateur
 * virtuel, 1 passage, avec la requete envoyee et la reponse recue de chaque etape.
 *
 * Memes regles que HttpClientExecutionEngine : authentification de l'application resolue
 * une seule fois (login LOGIN_TOKEN compris), variable ${authToken}, premiere ligne du CSV,
 * variables capturees transmises aux etapes suivantes, en-tete de l'etape prioritaire sur
 * celui de l'authentification, timeout par etape, politique de redirection par etape,
 * succes = code attendu (ou 2xx-3xx) + assertion "contient", arret au premier echec.
 *
 * Differences assumees (test de verification, pas de charge) : aucune Execution ni Metric
 * persistee, think time ignore, corps de reponse TOUJOURS lu (5 Mo max evalues, 100 Ko
 * renvoyes), delai global borne (60 s par defaut). Aucun contenu n'est journalise.
 */
@Component
public class StepTestRunner {

    public static final int DISPLAY_BODY_LIMIT_BYTES = 100 * 1024;
    static final int EVALUATION_BODY_LIMIT_BYTES = 5 * 1024 * 1024;

    private static final Pattern ABSOLUTE_URL = Pattern.compile("^(https?)://([^/?#]+)(.*)$", Pattern.CASE_INSENSITIVE);

    /** Progression en direct (flux NDJSON) - toutes les methodes sont optionnelles. */
    public interface Listener {
        Listener NONE = new Listener() { };

        default void authenticated(AuthResult auth) { }

        default void stepStarted(int index, StepExecutionSpec step) { }

        default void stepFinished(StepResult result) { }
    }

    private final long defaultTimeoutSeconds;
    private final long globalTimeoutSeconds;
    private final ObjectMapper objectMapper;
    private final ApplicationAuthenticator authenticator;
    /** Identifiant du proxy d'entreprise, ajoute aux requetes qui passent par lui (voir ProxyAuthorization). */
    private final com.loadpilot.backend.service.http.ProxyAuthorization proxyAuthorization;

    @org.springframework.beans.factory.annotation.Autowired
    public StepTestRunner(
            @Value("${app.execution.timeout-seconds:10}") long defaultTimeoutSeconds,
            @Value("${app.test-run.global-timeout-seconds:60}") long globalTimeoutSeconds,
            ObjectMapper objectMapper) {
        this(defaultTimeoutSeconds, globalTimeoutSeconds, objectMapper, com.loadpilot.backend.service.http.ProxyAuthorization.current());
    }

    /** Identifiants de proxy fournis (tests). */
    StepTestRunner(long defaultTimeoutSeconds, long globalTimeoutSeconds, ObjectMapper objectMapper,
            com.loadpilot.backend.service.http.ProxyAuthorization proxyAuthorization) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.globalTimeoutSeconds = globalTimeoutSeconds;
        this.objectMapper = objectMapper;
        this.proxyAuthorization = proxyAuthorization;
        this.authenticator = new ApplicationAuthenticator(objectMapper, proxyAuthorization);
    }

    public long globalTimeoutSeconds() {
        return globalTimeoutSeconds;
    }

    public TestRunResponse run(TestRunPlan plan, Listener listener) {
        long startedAt = System.nanoTime();
        long deadline = startedAt + TimeUnit.SECONDS.toNanos(globalTimeoutSeconds);
        ApplicationAuthSpec authSpec = plan.auth() == null ? ApplicationAuthSpec.none() : plan.auth();
        Set<String> secrets = new LinkedHashSet<>(configuredSecrets(authSpec));
        for (Map<String, String> source : List.of(plan.csvRow(), plan.scenarioVariables(), plan.providedVariables())) {
            source.forEach((name, value) -> {
                if (TestRunMasking.isSensitiveName(name) && value != null) secrets.add(value);
            });
        }

        ResolvedAuth resolvedAuth = null;
        VariableContext variables = new VariableContext(plan.scenarioVariables(), null);
        AuthResult authResult;
        long authStartedAt = System.nanoTime();
        try {
            resolvedAuth = authenticator.authenticate(authSpec, plan.applicationBaseUrl(), defaultTimeoutSeconds, plan.network());
            secrets.addAll(resolvedSecrets(resolvedAuth));
            variables = new VariableContext(plan.scenarioVariables(), resolvedAuth.token());
            authResult = new AuthResult(authType(authSpec), true, describeAuth(authSpec), elapsedMs(authStartedAt));
        } catch (ApplicationAuthenticationException e) {
            authResult = new AuthResult(authType(authSpec), false,
                    "Authentification de l'application échouée : " + TestRunMasking.maskSecrets(e.getMessage(), secrets) + ".",
                    elapsedMs(authStartedAt));
        }
        listener.authenticated(authResult);
        // Utilisateur virtuel n°1, premiere iteration : 1re ligne CSV ; variables fournies (test
        // d'une seule etape) = captures des etapes precedentes.
        variables.startIteration(plan.csvRow());
        VariableContext context = variables;
        plan.providedVariables().forEach(context::capture);

        // Cookies de session geres automatiquement, comme pour un utilisateur virtuel du moteur
        // (un pot pour ce test, commun aux deux clients).
        VirtualUserCookieJar cookies = new VirtualUserCookieJar();
        HttpClient following = newClient(HttpClient.Redirect.NORMAL, cookies, plan.network());
        HttpClient notFollowing = newClient(HttpClient.Redirect.NEVER, cookies, plan.network());

        List<StepResult> results = new ArrayList<>();
        String skipReason = resolvedAuth == null ? "Non exécutée : l'authentification de l'application a échoué." : null;
        String skipKind = resolvedAuth == null ? "AUTH_FAILED" : null;
        boolean timedOut = false;
        List<StepExecutionSpec> steps = plan.steps();
        try {
            for (int i = 0; i < steps.size(); i++) {
                StepExecutionSpec step = steps.get(i);
                int index = i + 1;
                UrlWarning urlWarning = urlWarning(plan.applicationBaseUrl(), step.url());
                if (skipReason == null && System.nanoTime() >= deadline) {
                    timedOut = true;
                    skipReason = "Non exécutée : délai global du test dépassé (" + globalTimeoutSeconds + " s).";
                    skipKind = "GLOBAL_TIMEOUT";
                }
                if (skipReason == null && Thread.currentThread().isInterrupted()) {
                    skipReason = "Non exécutée : test interrompu.";
                    skipKind = "INTERRUPTED";
                }
                StepResult result;
                if (skipReason != null) {
                    result = new StepResult(index, step.stepId(), step.name(), TestRunResponse.SKIPPED, false, skipReason,
                            skipKind, null, null, null, urlWarning, List.of());
                } else {
                    listener.stepStarted(index, step);
                    int stepNumber = i < plan.stepNumbers().size() ? plan.stepNumbers().get(i) : index;
                    result = executeStep(index, stepNumber, step, plan, resolvedAuth, context, secrets,
                            deadline, following, notFollowing, urlWarning);
                    if (!result.success()) {
                        timedOut |= "GLOBAL_TIMEOUT".equals(result.errorKind());
                        skipReason = "Non exécutée : l'étape " + index + " « " + step.name() + " » a échoué.";
                        skipKind = "PREVIOUS_FAILED";
                    }
                }
                results.add(result);
                listener.stepFinished(result);
            }
        } finally {
            // Appels synchrones termines (reponse lue, ou attente abandonnee) : clients fermes tout de suite.
            HttpClients.shutdownQuietly(following);
            HttpClients.shutdownQuietly(notFollowing);
        }

        int executed = (int) results.stream().filter(r -> !TestRunResponse.SKIPPED.equals(r.status())).count();
        int passed = (int) results.stream().filter(r -> TestRunResponse.PASSED.equals(r.status())).count();
        Map<String, String> reusableVariables = new LinkedHashMap<>();
        context.snapshot().forEach((name, value) -> {
            if (value != null && !ResolvedAuth.TOKEN_VARIABLE.equals(name) && !TestRunMasking.isSensitiveName(name)
                    && !secrets.contains(value)) {
                reusableVariables.put(name, value);
            }
        });
        return new TestRunResponse(plan.scenarioId(), plan.applicationBaseUrl(),
                authResult.success() && passed == steps.size(), steps.size(), executed, passed,
                elapsedMs(startedAt), timedOut, authResult, results, reusableVariables);
    }

    private StepResult executeStep(int index, int stepNumber, StepExecutionSpec step, TestRunPlan plan, ResolvedAuth auth,
            VariableContext variables, Set<String> secrets, long deadline, HttpClient following,
            HttpClient notFollowing, UrlWarning urlWarning) {
        Set<String> authHeaderNames = lowerCase(auth.headers().keySet());
        Set<String> authParamNames = lowerCase(auth.queryParams().keySet());

        // Meme construction que le moteur (RequestBuilder) : variables de chemin et query
        // encodees, priorite capture > CSV > scenario > authToken, puis authentification.
        RequestBuilder.BuiltRequest built = RequestBuilder.build(step, plan.applicationBaseUrl(), variables::get, objectMapper);
        List<String> warnings = new ArrayList<>(built.warnings());
        String url = auth.applyToUrl(built.url());
        String body = built.body();
        boolean hasBody = body != null && !body.isBlank();
        Map<String, String> stepHeaders = built.headers();
        Map<String, String> sentHeaders = new LinkedHashMap<>(stepHeaders);
        Set<String> stepHeaderNames = lowerCase(stepHeaders.keySet());
        auth.headers().forEach((name, value) -> {
            if (!stepHeaderNames.contains(name.toLowerCase(Locale.ROOT))) sentHeaders.put(name, value);
        });
        boolean followRedirects = !Boolean.FALSE.equals(step.followRedirects());
        // Arrondi superieur : le delai global exact est applique par future.get(...) plus bas.
        long remainingSeconds = Math.max(1, (long) Math.ceil((deadline - System.nanoTime()) / 1e9));
        long stepTimeout = step.timeoutSeconds() != null ? step.timeoutSeconds() : defaultTimeoutSeconds;
        long effectiveTimeout = Math.min(stepTimeout, remainingSeconds);

        SentRequest sent = new SentRequest(step.method().name(),
                TestRunMasking.maskUrl(url, authParamNames, secrets),
                TestRunMasking.maskHeaders(sentHeaders, authHeaderNames, secrets),
                hasBody ? TestRunMasking.maskBody(body, secrets) : null, followRedirects, effectiveTimeout);

        String expected = step.expectedStatus() != null ? String.valueOf(step.expectedStatus()) : "2xx-3xx";
        boolean hasAssertion = step.assertionBodyContains() != null && !step.assertionBodyContains().isBlank();
        boolean captureConfigured = step.captureVariableName() != null && !step.captureVariableName().isBlank()
                && step.captureJsonPath() != null && !step.captureJsonPath().isBlank();
        boolean hasMaxTime = StepChecks.hasMaxResponseTime(step.maxResponseTimeMs());
        boolean hasJson = step.hasJsonAssertion();
        String jsonOperator = step.assertionJsonOperator() != null ? step.assertionJsonOperator().name()
                : com.loadpilot.backend.enums.JsonAssertionOperator.EQUALS.name();
        String jsonExpected = step.assertionJsonValue() == null ? "" : TestRunMasking.maskSecrets(step.assertionJsonValue(), secrets);
        Checks notEvaluated = new Checks(new StatusCheck(expected, null, false),
                hasAssertion ? new AssertionCheck(TestRunMasking.maskSecrets(step.assertionBodyContains(), secrets), null) : null,
                captureConfigured ? new CaptureCheck(step.captureVariableName(), step.captureJsonPath(), false, null, false,
                        "Non évaluée : aucune réponse reçue.") : null,
                hasMaxTime ? new ResponseTimeCheck(step.maxResponseTimeMs(), null, null) : null,
                hasJson ? new JsonCheck(step.assertionJsonPath().trim(), jsonOperator, jsonExpected, null,
                        "Non évaluée : aucune réponse reçue.") : null);

        // Comme le moteur : jamais de requete envoyee avec un ${...} brut (URL, en-tetes ou corps).
        if (!built.missing().isEmpty()) {
            return failed(index, step, RequestBuilder.missingMessage(built.missing(), stepNumber, plan.captureStepByVariable()),
                    "UNRESOLVED_VARIABLE", sent, notEvaluated, urlWarning, warnings);
        }

        HttpRequest request;
        try {
            request = buildRequest(step, url, body, hasBody, stepHeaders, auth, effectiveTimeout, plan.network());
        } catch (InvalidRequestException e) {
            return failed(index, step, e.getMessage(), "INVALID_REQUEST", sent, notEvaluated, urlWarning, warnings);
        }

        HttpClient client = followRedirects ? following : notFollowing;
        long startedAt = System.nanoTime();
        CompletableFuture<HttpResponse<BodyData>> future =
                client.sendAsync(request, info -> limitedBody(EVALUATION_BODY_LIMIT_BYTES));
        HttpResponse<BodyData> response;
        try {
            response = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return failed(index, step, "Délai global du test dépassé (" + globalTimeoutSeconds + " s) en attendant la réponse.",
                    "GLOBAL_TIMEOUT", sent, notEvaluated, urlWarning, warnings);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return failed(index, step, "Test interrompu.", "INTERRUPTED", sent, notEvaluated, urlWarning, warnings);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException && e.getCause().getCause() != null
                    ? e.getCause().getCause() : e.getCause();
            if (cause instanceof IOException io) {
                String kind = NetworkErrors.kind(io);
                String message = "TIMEOUT".equals(kind) && !(io instanceof java.net.http.HttpConnectTimeoutException)
                        ? "Délai d'attente dépassé (" + effectiveTimeout + " s) : aucune réponse du serveur."
                        : capitalize(NetworkErrors.describe(io, effectiveTimeout)) + " — " + originOf(url) + ".";
                return failed(index, step, message, kind, sent, notEvaluated, urlWarning, warnings);
            }
            return failed(index, step, "Erreur pendant l'appel (" + (cause == null ? "inconnue" : cause.getClass().getSimpleName()) + ").",
                    "NETWORK", sent, notEvaluated, urlWarning, warnings);
        }
        long durationMs = elapsedMs(startedAt);

        int status = response.statusCode();
        String contentType = response.headers().firstValue("content-type").orElse(null);
        BodyData data = response.body();
        Charset charset = charsetOf(contentType);
        String fullText = new String(data.kept(), charset);
        if (data.total() > data.kept().length) {
            warnings.add("Réponse de plus de 5 Mo : assertion et capture évaluées sur les 5 premiers Mo.");
        }

        boolean statusOk = step.expectedStatus() != null ? status == step.expectedStatus() : status >= 200 && status < 400;
        Boolean assertionOk = hasAssertion ? fullText.contains(step.assertionBodyContains()) : null;
        // Controles optionnels, evalues comme dans le moteur (StepChecks) et en plus des precedents.
        String jsonProblem = hasJson ? StepChecks.jsonAssertionProblem(objectMapper, fullText, step.assertionJsonPath(),
                step.assertionJsonOperator(), step.assertionJsonValue()) : null;
        String timeProblem = StepChecks.responseTimeProblem(step.maxResponseTimeMs(), durationMs);
        boolean success = statusOk && !Boolean.FALSE.equals(assertionOk) && jsonProblem == null && timeProblem == null;

        // Capture apres evaluation, quel que soit le resultat de l'assertion (comme le moteur).
        CaptureCheck capture = null;
        if (captureConfigured) {
            boolean fromHeader = step.capturesFromHeader();
            boolean fromHtml = step.capturesFromHtml();
            HtmlExtractor.Match htmlMatch = fromHtml ? HtmlExtractor.find(fullText, step.captureJsonPath()) : null;
            String value = fromHtml ? htmlMatch.value()
                    : fromHeader ? ResponseHeaderExtractor.extract(response.headers(), step.captureJsonPath())
                    : JsonPathExtractor.extract(objectMapper, fullText, step.captureJsonPath());
            if (fromHtml && htmlMatch.count() > 1) {
                warnings.add(htmlMatch.count() + " éléments correspondent à « " + HtmlExtractor.parseSpec(step.captureJsonPath()).selector()
                        + " » : le premier de la page est utilisé. Précisez le sélecteur si ce n'est pas le bon.");
            }
            if (value != null) {
                variables.capture(step.captureVariableName().trim(), value);
                boolean sensitive = TestRunMasking.isSensitiveName(step.captureVariableName())
                        || TestRunMasking.isSensitiveName(step.captureJsonPath()) || secrets.contains(value)
                        || (fromHeader && TestRunMasking.isSensitiveHeaderCapture(step.captureJsonPath()))
                        || (fromHtml && TestRunMasking.isSensitiveHtmlCapture(step.captureJsonPath(), htmlMatch.elementName()));
                if (sensitive) secrets.add(value);
                capture = new CaptureCheck(step.captureVariableName(), step.captureJsonPath(), true,
                        sensitive ? TestRunMasking.MASK : value, sensitive,
                        "Variable ${" + step.captureVariableName() + "} capturée.");
            } else {
                // Raison precise (index hors limites, en-tete absent…), jamais le contenu de la reponse.
                String reason = fromHtml ? htmlMatch.reason()
                        : fromHeader ? ResponseHeaderExtractor.explain(response.headers(), step.captureJsonPath())
                        : JsonPathExtractor.explain(objectMapper, fullText, step.captureJsonPath());
                capture = new CaptureCheck(step.captureVariableName(), step.captureJsonPath(), false, null, false,
                        fromHeader || fromHtml ? "Introuvable : " + reason + "."
                                : isJson(fullText) ? "Introuvable à " + step.captureJsonPath() + (reason != null ? " : " + reason : "") + "."
                                : "Réponse non JSON : impossible de lire " + step.captureJsonPath() + ".");
                warnings.add("La variable ${" + step.captureVariableName() + "} ne sera pas disponible pour les étapes suivantes.");
            }
        }

        boolean truncated = data.kept().length > DISPLAY_BODY_LIMIT_BYTES || data.total() > data.kept().length;
        String displayed = data.kept().length > DISPLAY_BODY_LIMIT_BYTES
                ? new String(data.kept(), 0, DISPLAY_BODY_LIMIT_BYTES, charset) : fullText;
        String statusText = statusText(status);
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> responseHeaders.put(name, String.join(", ", values)));
        ReceivedResponse received = new ReceivedResponse(status, statusText, durationMs, data.total(), contentType,
                TestRunMasking.maskHeaders(responseHeaders, Set.of(), secrets),
                TestRunMasking.maskBody(displayed, secrets), truncated, !truncated && isJson(displayed));

        String error = !statusOk
                ? "Réponse HTTP " + status + (statusText.isEmpty() ? "" : " " + statusText) + " (attendu " + expected + ")."
                : Boolean.FALSE.equals(assertionOk)
                ? HttpClientExecutionEngine.missingContentMessage(TestRunMasking.maskSecrets(step.assertionBodyContains(), secrets))
                : jsonProblem != null ? TestRunMasking.maskSecrets(jsonProblem, secrets) + "."
                : timeProblem != null ? timeProblem + "."
                : null;
        Checks checks = new Checks(new StatusCheck(expected, status, statusOk),
                hasAssertion ? new AssertionCheck(TestRunMasking.maskSecrets(step.assertionBodyContains(), secrets), assertionOk) : null,
                capture,
                hasMaxTime ? new ResponseTimeCheck(step.maxResponseTimeMs(), durationMs, timeProblem == null) : null,
                hasJson ? new JsonCheck(step.assertionJsonPath().trim(), jsonOperator, jsonExpected, jsonProblem == null,
                        jsonProblem == null ? null : TestRunMasking.maskSecrets(jsonProblem, secrets) + ".") : null);
        boolean assertionFailed = Boolean.FALSE.equals(assertionOk) || jsonProblem != null;
        return new StepResult(index, step.stepId(), step.name(), success ? TestRunResponse.PASSED : TestRunResponse.FAILED,
                success, error, !statusOk ? "HTTP_STATUS" : assertionFailed ? "ASSERTION"
                        : timeProblem != null ? "RESPONSE_TIME" : null,
                sent, received, checks, urlWarning, List.copyOf(warnings));
    }

    private HttpRequest buildRequest(StepExecutionSpec step, String url, String body, boolean hasBody,
            Map<String, String> stepHeaders, ResolvedAuth auth, long timeoutSeconds,
            com.loadpilot.backend.service.http.NetworkProfile network) {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("URL invalide : l'URL finale n'est pas une adresse http(s) valide.");
        }
        HttpRequest.BodyPublisher publisher = hasBody
                ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody();
        switch (step.method()) {
            case GET -> builder.GET();
            case POST -> builder.POST(publisher);
            case PUT -> builder.PUT(publisher);
            case PATCH -> builder.method("PATCH", publisher);
            case DELETE -> builder.method("DELETE", publisher);
        }
        stepHeaders.forEach((name, value) -> {
            try {
                builder.header(name, value);
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("En-tête « " + name + " » refusé par le client HTTP "
                        + "(en-tête géré automatiquement ou nom/valeur invalide).");
            }
        });
        auth.applyHeaders(builder, stepHeaders.keySet());
        // Identifiant du proxy (de l'application, ou du serveur), seulement si la requete passe par lui.
        network.applyTo(builder, URI.create(url), proxyAuthorization);
        return builder.build();
    }

    /**
     * Avertissement quand l'etape appelle une URL absolue dont l'origine (schema, hote, port)
     * differe de celle de l'application - avec le chemin relatif propose a la place.
     */
    public static UrlWarning urlWarning(String applicationBaseUrl, String stepUrl) {
        if (stepUrl == null || applicationBaseUrl == null) return null;
        Matcher step = ABSOLUTE_URL.matcher(stepUrl.trim());
        Matcher base = ABSOLUTE_URL.matcher(applicationBaseUrl.trim());
        if (!step.matches() || !base.matches()) return null;
        String stepOrigin = origin(step.group(1), step.group(2));
        String baseOrigin = origin(base.group(1), base.group(2));
        if (stepOrigin.equals(baseOrigin)) return null;
        String path = step.group(3).isEmpty() ? "/" : step.group(3);
        String basePath = base.group(3).replaceAll("/+$", "");
        if (!basePath.isEmpty() && path.startsWith(basePath + "/")) {
            path = path.substring(basePath.length());
        }
        return new UrlWarning("Cette étape appelle " + stepOrigin + " alors que l'application testée est "
                + baseOrigin + ". Utilisez plutôt le chemin relatif " + path + ".", path);
    }

    private static String origin(String scheme, String authority) {
        String s = scheme.toLowerCase(Locale.ROOT);
        String hostPort = authority.substring(authority.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        boolean hasPort = hostPort.matches(".*:\\d+$");
        return s + "://" + (hasPort ? hostPort : hostPort + ("https".equals(s) ? ":443" : ":80"));
    }

    private static String originOf(String url) {
        Matcher m = ABSOLUTE_URL.matcher(url);
        return m.matches() ? m.group(1) + "://" + m.group(2).substring(m.group(2).lastIndexOf('@') + 1) : "URL invalide";
    }

    private StepResult failed(int index, StepExecutionSpec step, String error, String kind, SentRequest sent,
            Checks checks, UrlWarning urlWarning, List<String> warnings) {
        return new StepResult(index, step.stepId(), step.name(), TestRunResponse.FAILED, false, error, kind, sent,
                null, checks, urlWarning, List.copyOf(warnings));
    }

    /** Secrets de la configuration d'authentification (aussi utilises par le detail des requetes d'une execution). */
    static List<String> configuredSecrets(ApplicationAuthSpec spec) {
        List<String> secrets = new ArrayList<>();
        ApplicationAuthConfig c = spec.config();
        if (c == null) return secrets;
        if (c.token() != null) secrets.add(c.token());
        if (c.apiKeyValue() != null) secrets.add(c.apiKeyValue());
        if (c.password() != null) secrets.add(c.password());
        secrets.addAll(TestRunMasking.sensitiveJsonValues(c.loginBody()));
        return secrets;
    }

    static List<String> resolvedSecrets(ResolvedAuth auth) {
        List<String> secrets = new ArrayList<>();
        auth.headers().values().forEach(value -> {
            secrets.add(value);
            int space = value.indexOf(' ');
            if (space > 0) secrets.add(value.substring(space + 1));
        });
        secrets.addAll(auth.queryParams().values());
        if (auth.token() != null) secrets.add(auth.token());
        return secrets;
    }

    private static String authType(ApplicationAuthSpec spec) {
        return spec.type() == null ? "NONE" : spec.type().name();
    }

    private static String describeAuth(ApplicationAuthSpec spec) {
        ApplicationAuthConfig c = spec.config();
        if (spec.isNone()) return "Aucune authentification configurée sur l'application.";
        return switch (spec.type()) {
            case LOGIN_TOKEN -> "Connexion réussie : jeton obtenu via POST " + c.loginPath()
                    + " (une seule fois pour tout le test, disponible dans ${authToken}).";
            case BEARER_TOKEN -> "Jeton Bearer de l'application ajouté à chaque requête.";
            case API_KEY -> c.apiKeyLocation() == ApiKeyLocation.QUERY
                    ? "Clé API ajoutée au paramètre d'URL « " + c.apiKeyName() + " »."
                    : "Clé API ajoutée à l'en-tête « " + c.apiKeyName() + " ».";
            case BASIC -> "Authentification Basic ajoutée à chaque requête.";
            case NONE -> "Aucune authentification configurée sur l'application.";
        };
    }

    private HttpClient newClient(HttpClient.Redirect redirect, VirtualUserCookieJar cookies,
            com.loadpilot.backend.service.http.NetworkProfile network) {
        return network.configure(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(defaultTimeoutSeconds))
                .followRedirects(redirect)
                .cookieHandler(cookies))
                .build();
    }

    private boolean isJson(String text) {
        if (text == null) return false;
        String trimmed = text.stripLeading();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false;
        try {
            objectMapper.readTree(text);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String statusText(int status) {
        HttpStatus known = HttpStatus.resolve(status);
        return known == null ? "" : known.getReasonPhrase();
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            Matcher m = Pattern.compile("charset=\"?([\\w.:-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (RuntimeException ignored) {
                    // charset inconnu : UTF-8 par defaut
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Set<String> lowerCase(Set<String> names) {
        return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static String capitalize(String text) {
        return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static long elapsedMs(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static final class InvalidRequestException extends RuntimeException {
        InvalidRequestException(String message) {
            super(message);
        }
    }

    /** Corps conserve (au plus "limit" octets) et taille totale reellement recue. */
    record BodyData(byte[] kept, long total) {
    }

    private static HttpResponse.BodySubscriber<BodyData> limitedBody(int limit) {
        LimitedCollector collector = new LimitedCollector(limit);
        return HttpResponse.BodySubscribers.fromSubscriber(collector, c -> new BodyData(c.kept.toByteArray(), c.total));
    }

    /** Lit tout le flux (pour la taille reelle) sans jamais garder plus de "limit" octets. */
    private static final class LimitedCollector implements Flow.Subscriber<List<ByteBuffer>> {
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final int limit;
        private long total;

        LimitedCollector(int limit) {
            this.limit = limit;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                total += size;
                int room = limit - kept.size();
                if (room > 0) {
                    byte[] chunk = new byte[Math.min(room, size)];
                    buffer.get(chunk);
                    kept.write(chunk, 0, chunk.length);
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // l'erreur est propagee par BodySubscribers.fromSubscriber
        }

        @Override
        public void onComplete() {
            // la valeur finale est construite par le "finisher"
        }
    }
}
