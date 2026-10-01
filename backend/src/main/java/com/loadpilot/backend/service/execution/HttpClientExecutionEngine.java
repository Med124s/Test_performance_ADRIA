package com.loadpilot.backend.service.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadpilot.backend.enums.ExecutionStatus;
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

    public HttpClientExecutionEngine(
            @Value("${app.execution.timeout-seconds:10}") long timeoutSeconds,
            ObjectMapper objectMapper,
            ExecutorService loadTestExecutor) {
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        this.vuExecutor = loadTestExecutor;
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
            LoadTestSpec loadSpec, RunningExecutionHandle handle, List<Map<String, String>> vuVariableRows) {
        int virtualUsers = Math.max(1, loadSpec.virtualUsers());
        // Etalement reel du ramp-up : chaque VU demarre staggerMs apres le
        // precedent, jamais tous instantanement (voir rapport P0-A section 8).
        long staggerMs = (virtualUsers > 1 && loadSpec.rampUpSeconds() > 0)
                ? (loadSpec.rampUpSeconds() * 1000L) / Math.max(1, virtualUsers - 1)
                : 0L;
        Instant deadline = loadSpec.durationSeconds() != null
                ? Instant.now().plusSeconds(loadSpec.durationSeconds())
                : null;
        // Master prompt final (Lot A) - UN SEUL PacingGate partage par tous
        // les VUs de cette Execution (jamais un par VU, sinon le debit reel
        // serait targetRps * virtualUsers) ; null = aucun pacing configure,
        // comportement historique inchange.
        PacingGate pacingGate = loadSpec.targetRps() != null ? new PacingGate(loadSpec.targetRps()) : null;

        List<Future<List<StepOutcome>>> futures = new ArrayList<>();
        for (int vu = 0; vu < virtualUsers; vu++) {
            long startDelayMs = vu * staggerMs;
            // Distribution CYCLIQUE reelle d'une ligne CSV par VU (voir
            // ExecutionEngine#execute) - jamais un echec si moins de lignes
            // que de VUs. Copie MUTABLE et propre a ce VU (jamais la meme
            // instance Map partagee entre deux VUs, meme quand vuVariableRows
            // est vide) : les variables capturees dynamiquement (Lot B)
            // restent ainsi strictement isolees par VU.
            Map<String, String> variables = new java.util.HashMap<>(
                    vuVariableRows.isEmpty() ? Map.of() : vuVariableRows.get(vu % vuVariableRows.size()));
            Future<List<StepOutcome>> future = vuExecutor.submit(
                    () -> runVirtualUser(steps, applicationBaseUrl, startDelayMs, deadline, loadSpec, handle, variables, pacingGate));
            handle.registerFuture(future);
            futures.add(future);
        }

        List<StepOutcome> allOutcomes = new ArrayList<>();
        for (Future<List<StepOutcome>> future : futures) {
            try {
                allOutcomes.addAll(future.get());
            } catch (java.util.concurrent.CancellationException e) {
                // VU reellement interrompu par une annulation (Future.cancel(true))
                // avant meme d'avoir pu retourner ses resultats partiels deja
                // collectes en interne - rien de plus a recuperer pour ce VU.
                log.debug("Utilisateur virtuel annule avant completion.");
            } catch (ExecutionException e) {
                log.warn("Utilisateur virtuel termine en erreur inattendue : {}", e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean anyFailure = allOutcomes.stream().anyMatch(o -> !o.success());
        ExecutionStatus status = (!allOutcomes.isEmpty() && !anyFailure) ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED;
        String errorMessage = anyFailure
                ? allOutcomes.stream().filter(o -> !o.success()).findFirst().map(StepOutcome::error).orElse("Echec inconnu")
                : (allOutcomes.isEmpty() ? "Aucune requete n'a ete executee." : null);

        return new ScenarioExecutionOutcome(allOutcomes, status, errorMessage);
    }

    /**
     * Passage produit reel (2026-09-30) — "Tester les etapes
     * selectionnees" (voir StepController /test-batch) : envoie UNE VRAIE
     * requete HTTP par etape fournie, en parallele (threads virtuels, comme
     * les utilisateurs virtuels d'une vraie Execution), jamais une charge
     * repetee - ce n'est PAS une Execution (aucune ligne Execution/
     * ExecutionStepResult creee, voir StepServiceImpl.testBatch). Resultat
     * reel par etape (HTTP status/temps/erreur/assertion), jamais simule.
     */
    @Override
    public List<StepOutcome> testSteps(List<StepExecutionSpec> steps, String applicationBaseUrl) {
        HttpClient clientFollowing = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpClient clientNotFollowing = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        List<Future<StepOutcome>> futures = new ArrayList<>();
        for (StepExecutionSpec step : steps) {
            futures.add(vuExecutor.submit(() -> {
                HttpClient client = Boolean.FALSE.equals(step.followRedirects()) ? clientNotFollowing : clientFollowing;
                return executeStep(client, step, applicationBaseUrl, new java.util.HashMap<>());
            }));
        }

        List<StepOutcome> results = new ArrayList<>();
        for (Future<StepOutcome> future : futures) {
            try {
                results.add(future.get());
            } catch (ExecutionException e) {
                log.warn("Test d'etape termine en erreur inattendue : {}", e.getCause() != null ? e.getCause().getClass().getSimpleName() : e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return results;
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
    private List<StepOutcome> runVirtualUser(List<StepExecutionSpec> steps, String applicationBaseUrl,
            long startDelayMs, Instant deadline, LoadTestSpec loadSpec, RunningExecutionHandle handle,
            Map<String, String> variables, PacingGate pacingGate) {
        List<StepOutcome> results = new ArrayList<>();
        if (!sleep(startDelayMs) || handle.isCancelled()) {
            return results;
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
        // P1-Q Etape B - DEUX clients construits ici (NORMAL et NEVER) :
        // HttpClient fixe sa politique de redirection a la CONSTRUCTION,
        // jamais par requete - un Step avec followRedirects=false utilise le
        // second client, tous les autres (null ou true) le premier
        // (comportement global historique inchange par defaut).
        HttpClient clientFollowing = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpClient clientNotFollowing = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        // Passage produit reel (2026-09-30) - StopMode.MANUAL desactive
        // TOUTES les conditions d'arret automatique (duree/iterations/passe
        // unique par defaut) : seule une annulation explicite (voir
        // handle.isCancelled()) arrete alors ce VU. AUTO reproduit EXACTEMENT
        // le comportement historique ci-dessous, inchange.
        boolean manualStop = loadSpec.stopMode() == com.loadpilot.backend.enums.StopMode.MANUAL;

        int iteration = 0;
        while (true) {
            if (handle.isCancelled()) break;
            if (!manualStop) {
                if (deadline != null && Instant.now().isAfter(deadline)) break;
                if (loadSpec.iterations() != null && iteration >= loadSpec.iterations()) break;
                if (deadline == null && loadSpec.iterations() == null && iteration >= 1) break;
            }

            // Une iteration en echec n'arrete PAS les iterations suivantes de
            // CE VU (seule l'iteration courante s'arrete au premier echec,
            // via ce break) - voir politique de statut global en tete de
            // classe. La garde de sortie de boucle du haut se charge deja du
            // mode "1 passe" par defaut au tour suivant.
            for (StepExecutionSpec step : steps) {
                if (handle.isCancelled()) break;
                // Master prompt final (Lot A) - un seul debit cible pour
                // TOUTE l'Execution (voir execute()) : chaque requete HTTP,
                // de n'importe quel VU, reserve son slot ici avant d'etre
                // envoyee. Sortie immediate si l'attente est interrompue par
                // une annulation reelle (jamais un blocage infini : la duree
                // d'attente est toujours finie, voir PacingGate).
                if (pacingGate != null && !pacingGate.acquire()) break;
                HttpClient client = Boolean.FALSE.equals(step.followRedirects()) ? clientNotFollowing : clientFollowing;
                StepOutcome outcome = executeStep(client, step, applicationBaseUrl, variables);
                results.add(outcome);
                // P1-Q Etape B - pause specifique a CETTE etape, en plus du
                // think time de Scenario ci-dessous (jamais a la place) -
                // jamais appliquee apres une annulation ni si non configuree.
                if (!handle.isCancelled() && step.thinkTimeMs() != null && step.thinkTimeMs() > 0
                        && !sleep(step.thinkTimeMs())) {
                    break;
                }
                // Passage produit reel (2026-09-30) - pacing REEL apres
                // l'envoi de cette etape, en plus (jamais a la place) du
                // think time ci-dessus - meme garde (jamais apres une
                // annulation, jamais si non configure).
                if (!handle.isCancelled() && step.pacingAfterMs() != null && step.pacingAfterMs() > 0
                        && !sleep(step.pacingAfterMs())) {
                    break;
                }
                if (!outcome.success()) {
                    break;
                }
            }
            iteration++;
            // Think time uniquement APRES une iteration, jamais apres une
            // annulation, jamais applique si nul.
            if (!handle.isCancelled() && loadSpec.thinkTimeMs() > 0 && !sleep(loadSpec.thinkTimeMs())) {
                break;
            }
        }
        return results;
    }

    /** Sommeil reel interruptible - renvoie false si interrompu (annulation),
     * pour que l'appelant arrete immediatement plutot que de continuer. */
    private boolean sleep(long millis) {
        if (millis <= 0) return true;
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private StepOutcome executeStep(HttpClient client, StepExecutionSpec step, String applicationBaseUrl,
            Map<String, String> variables) {
        // P1-Q Etape B - substitution reelle des variables ${nom} (CSV data
        // source, voir VariableResolver) AVANT resolution de l'URL de base -
        // "${baseUrl}" n'a besoin d'aucune variable dediee, deja resolu par
        // UrlResolver.resolve() ci-dessous.
        String rawUrl = VariableResolver.substitute(step.url(), variables);
        String url = UrlResolver.resolve(applicationBaseUrl, rawUrl);
        long effectiveTimeoutSeconds = step.timeoutSeconds() != null ? step.timeoutSeconds() : timeoutSeconds;
        long startedAt = System.nanoTime();

        boolean captureConfigured = step.captureVariableName() != null && !step.captureVariableName().isBlank()
                && step.captureJsonPath() != null && !step.captureJsonPath().isBlank();
        boolean hasAssertion = step.assertionBodyContains() != null && !step.assertionBodyContains().isBlank();

        try {
            HttpRequest request = buildRequest(step, url, variables, effectiveTimeoutSeconds);
            boolean readBody = hasAssertion || captureConfigured;
            // P0-B (protection des ressources, section 8) : par defaut, le
            // corps de la reponse n'est JAMAIS lu ni stocke - le bufferiser
            // integralement en memoire via BodyHandlers.ofString() etait un
            // vrai risque reel identifie (une cible renvoyant une reponse
            // enorme, multipliee par le nombre d'utilisateurs virtuels
            // concurrents, pouvait epuiser le tas de la JVM pour un contenu
            // jamais utilise). discarding() ne bufferise rien.
            //
            // P1-Q Etape B / Lot B - EXCEPTION assumee et explicite :
            // uniquement quand ce Step configure reellement une assertion de
            // contenu OU une capture de variable (readBody=true), le corps
            // est lu (ofString()) - un choix opt-in par etape, jamais impose
            // aux Steps existants/non configures (comportement historique
            // inchange pour eux).
            HttpResponse<String> response = client.send(request,
                    readBody ? HttpResponse.BodyHandlers.ofString() : discardingAsString());
            long elapsed = elapsedMs(startedAt);
            boolean statusOk = evaluateSuccess(step, response.statusCode());
            boolean assertionOk = !hasAssertion || response.body().contains(step.assertionBodyContains());
            boolean success = statusOk && assertionOk;
            String error = !statusOk
                    ? "Reponse HTTP " + response.statusCode() + " (attendu " + describeExpectation(step) + ")"
                    : !assertionOk
                    ? "Assertion echouee : la reponse ne contient pas le contenu attendu."
                    : null;
            // Master prompt final (Lot B) - capture APRES evaluation du
            // succes/echec (jamais avant), mais QUEL QUE SOIT le resultat de
            // l'assertion : un jeton d'authentification reste utile aux
            // etapes suivantes meme si une assertion sans rapport a echoue.
            // Isolation stricte par VU deja garantie par execute() (chaque
            // VU recoit sa PROPRE instance mutable de "variables", jamais
            // partagee - voir HashMap cree dans execute()). Reponse non-JSON/
            // chemin introuvable -> JsonPathExtractor renvoie null, jamais
            // d'exception, aucune variable produite dans ce cas.
            if (captureConfigured) {
                String captured = JsonPathExtractor.extract(objectMapper, response.body(), step.captureJsonPath());
                if (captured != null) {
                    variables.put(step.captureVariableName(), captured);
                }
            }
            return new StepOutcome(step.stepId(), response.statusCode(), elapsed, success, error, Instant.now());
        } catch (HttpTimeoutException e) {
            return new StepOutcome(step.stepId(), null, elapsedMs(startedAt), false,
                    "Delai d'attente depasse (" + effectiveTimeoutSeconds + "s)", Instant.now());
        } catch (IOException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.debug("Etape '{}' echouee (technique) : {}", step.name(), e.getClass().getSimpleName());
            return new StepOutcome(step.stepId(), null, elapsedMs(startedAt), false, message, Instant.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StepOutcome(step.stepId(), null, elapsedMs(startedAt), false, "Execution interrompue.", Instant.now());
        } catch (IllegalArgumentException e) {
            return new StepOutcome(step.stepId(), null, 0, false, "URL invalide : " + e.getClass().getSimpleName(), Instant.now());
        }
    }

    /** Meme garantie que BodyHandlers.discarding() (rien bufferise) mais
     * type Void->String pour partager la meme signature client.send(...)
     * que le cas "assertion" (readBody=true) ci-dessus - jamais de corps
     * lu ici, body() renvoie simplement null. */
    private HttpResponse.BodyHandler<String> discardingAsString() {
        return responseInfo -> HttpResponse.BodySubscribers.replacing(null);
    }

    private HttpRequest buildRequest(StepExecutionSpec step, String url, Map<String, String> variables,
            long effectiveTimeoutSeconds) {
        String substitutedBody = VariableResolver.substitute(step.body(), variables);
        HttpRequest.BodyPublisher bodyPublisher = (substitutedBody != null && !substitutedBody.isBlank())
                ? HttpRequest.BodyPublishers.ofString(substitutedBody)
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

        String substitutedHeaders = VariableResolver.substitute(step.headers(), variables);
        parseHeaders(substitutedHeaders).forEach(builder::header);
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

    private String describeExpectation(StepExecutionSpec step) {
        return step.expectedStatus() != null ? String.valueOf(step.expectedStatus()) : "2xx-3xx";
    }

    private long elapsedMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
