package com.loadpilot.backend.service.impl;

import com.loadpilot.backend.specification.ApplicationSpecifications;
import com.loadpilot.backend.dto.response.PagedResponse;
import com.loadpilot.backend.dto.request.ApplicationRequest;
import com.loadpilot.backend.dto.response.ApplicationResponse;
import com.loadpilot.backend.dto.response.ApplicationTestResponse;
import com.loadpilot.backend.entity.AppUser;
import com.loadpilot.backend.entity.Application;
import com.loadpilot.backend.enums.ApplicationAuthType;
import com.loadpilot.backend.enums.ApplicationStatus;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.enums.AuditResult;
import com.loadpilot.backend.exception.ConflictException;
import com.loadpilot.backend.exception.ResourceNotFoundException;
import com.loadpilot.backend.mapper.ApplicationMapper;
import com.loadpilot.backend.repository.ApplicationRepository;
import com.loadpilot.backend.repository.ScenarioRepository;
import com.loadpilot.backend.security.CurrentUser;
import com.loadpilot.backend.service.AppUserSyncService;
import com.loadpilot.backend.service.ApplicationService;
import com.loadpilot.backend.service.AuditLogService;
import com.loadpilot.backend.service.auth.ApplicationAuthSpec;
import com.loadpilot.backend.service.auth.ApplicationAuthSupport;
import com.loadpilot.backend.service.execution.ApplicationAuthenticationException;
import com.loadpilot.backend.service.execution.ResolvedAuth;
import com.loadpilot.backend.service.execution.UrlResolver;
import com.loadpilot.backend.service.http.HttpAvailabilityChecker;
import com.loadpilot.backend.service.http.HttpAvailabilityResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit (Phase 12) : chaque methode d'ecriture appelle
 * auditLogService.record(...) comme DERNIERE instruction avant de retourner
 * (le flush/delete a deja ete envoye a la DB a ce stade - aucune operation
 * susceptible d'echouer ne suit), ou dans le bloc catch en cas d'echec.
 * record() n'est jamais transactionnel lui-meme et absorbe deja toute
 * exception (voir AuditLogServiceImpl) : cet appel ne peut donc jamais
 * transformer une operation metier reussie en echec, ni l'inverse.
 */
@Service
@RequiredArgsConstructor
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final ScenarioRepository scenarioRepository;
    private final ApplicationMapper applicationMapper;
    private final AppUserSyncService appUserSyncService;
    private final HttpAvailabilityChecker availabilityChecker;
    private final AuditLogService auditLogService;
    private final ApplicationAuthSupport authSupport;
    private final DeletionServiceImpl deletionService;

    @Value("${app.availability.timeout-seconds:5}")
    private long timeoutSeconds;

    @Override
    @Transactional
    public ApplicationResponse create(ApplicationRequest request, CurrentUser currentUser) {
        try {
            AppUser createdBy = appUserSyncService.sync(currentUser);

            Application application = applicationMapper.toEntity(request);
            application.setCreatedBy(createdBy);
            authSupport.apply(request.auth(), application, true);
            // Aucun test n'a encore ete lance pour cette application : pas de
            // statut invente (voir ApplicationStatus).

            // saveAndFlush (pas save) : @CreationTimestamp/@UpdateTimestamp ne
            // sont renseignes par Hibernate qu'au moment du flush reel (INSERT
            // execute) - sans flush immediat, la reponse construite juste apres
            // renverrait un createdAt/updatedAt encore nul.
            Application saved = applicationRepository.saveAndFlush(application);
            auditLogService.record(AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.SUCCESS,
                    "Application created: " + saved.getName());
            return applicationMapper.toResponse(saved);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.CREATE, AuditModule.APPLICATION, AuditResult.FAILURE,
                    "Application creation failed: " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationResponse getById(UUID id) {
        return applicationMapper.toResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApplicationResponse> list(boolean archived) {
        List<Application> applications = applicationRepository.findByArchived(archived, Sort.by(Sort.Direction.DESC, "createdAt"));
        return applicationMapper.toResponseList(applications);
    }

    /** Champs de tri autorises (jamais un champ arbitraire venu de l'URL). */
    private static final java.util.Set<String> SORTABLE = java.util.Set.of("name", "url", "status", "createdAt", "updatedAt");

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<ApplicationResponse> page(int page, int size, String sort, String search, String status, boolean archived) {
        int safeSize = Math.max(5, Math.min(100, size));
        int safePage = Math.max(0, page);
        org.springframework.data.domain.Page<Application> result = applicationRepository.findAll(
                ApplicationSpecifications.matching(search, parseStatusFilter(status), archived),
                org.springframework.data.domain.PageRequest.of(safePage, safeSize, parseSort(sort)));
        return new PagedResponse<>(applicationMapper.toResponseList(result.getContent()), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) return Sort.by(Sort.Direction.DESC, "createdAt");
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORTABLE.contains(field)) {
            throw new com.loadpilot.backend.exception.LoadConfigurationException(
                    "Tri non autorisé : " + field + " (champs possibles : name, url, status, createdAt, updatedAt).");
        }
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        // Tri secondaire stable : l'ordre des lignes ne change pas d'une page a l'autre.
        return Sort.by(direction, field).and(Sort.by(Sort.Direction.ASC, "id"));
    }

    /** "UNTESTED" = jamais testee (status null) ; vide = tous les status. */
    private static ApplicationSpecifications.StatusFilter parseStatusFilter(String status) {
        if (status == null || status.isBlank()) return ApplicationSpecifications.StatusFilter.any();
        if (status.equalsIgnoreCase("UNTESTED")) return ApplicationSpecifications.StatusFilter.untested();
        try {
            return ApplicationSpecifications.StatusFilter.of(ApplicationStatus.valueOf(status.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new com.loadpilot.backend.exception.LoadConfigurationException(
                    "Statut inconnu : " + status + " (CONNECTED, FAILED, ERROR ou UNTESTED).");
        }
    }

    @Override
    @Transactional
    public ApplicationResponse update(UUID id, ApplicationRequest request) {
        try {
            Application application = findOrThrow(id);
            applicationMapper.updateEntityFromRequest(request, application);
            authSupport.apply(request.auth(), application, false);
            // Toute modification (URL, authentification...) invalide le
            // resultat du dernier test : il sera remplace par le prochain.
            application.setStatus(null);
            application.setStatusMessage(null);
            application.setStatusCheckedAt(null);
            Application saved = applicationRepository.saveAndFlush(application);
            auditLogService.record(AuditAction.UPDATE, AuditModule.APPLICATION, AuditResult.SUCCESS,
                    "Application updated: " + saved.getName());
            return applicationMapper.toResponse(saved);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.UPDATE, AuditModule.APPLICATION, AuditResult.FAILURE,
                    "Application update failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        // Suppression DEFINITIVE explicitement demandee (SUPER_ADMIN) : cascade
        // complete dans une seule transaction, refusee si une execution est en
        // cours - voir DeletionServiceImpl (audit inclus). L'archivage est
        // l'alternative par defaut proposee par l'interface.
        deletionService.deleteApplicationPermanently(id);
    }

    @Override
    @Transactional
    public ApplicationTestResponse testAvailability(UUID id) {
        try {
            Application application = findOrThrow(id);

            // Un test par mode d'authentification, toujours contre
            // l'application cible reelle (jamais CONNECTED avec des
            // identifiants faux) :
            //   NONE             -> GET sur l'URL de l'application
            //   LOGIN_TOKEN      -> POST reel sur l'endpoint de connexion
            //   BEARER / API_KEY / BASIC -> 2 GET sur l'endpoint de verification
            // Aucun message ne contient de secret (voir NetworkErrors,
            // ApplicationAuthenticator).
            ApplicationAuthSpec spec = authSupport.toSpec(application);
            Duration timeout = Duration.ofSeconds(timeoutSeconds);
            CheckOutcome outcome = switch (spec.type()) {
                case LOGIN_TOKEN -> verifyLogin(application);
                case BEARER_TOKEN, API_KEY, BASIC -> verifyCredentials(application, spec, timeout);
                case NONE -> checkPublicUrl(application, timeout);
            };
            return finishTest(application, outcome);
        } catch (RuntimeException e) {
            auditLogService.record(AuditAction.TEST, AuditModule.APPLICATION, AuditResult.FAILURE,
                    "Application availability test failed for id " + id + ": " + e.getClass().getSimpleName());
            throw e;
        }
    }

    /** Resultat d'un test avant enregistrement. */
    private record CheckOutcome(ApplicationStatus status, String message, HttpAvailabilityResult result) {
    }

    private static HttpAvailabilityResult noResponse(String error) {
        return new HttpAvailabilityResult(false, null, 0, error);
    }

    /** Aucune : GET sur l'URL de l'application -> 2xx/3xx CONNECTED. */
    private CheckOutcome checkPublicUrl(Application application, Duration timeout) {
        HttpAvailabilityResult result = availabilityChecker.check(application.getUrl(), timeout);
        Integer code = result.httpStatus();
        if (!result.success()) {
            return new CheckOutcome(ApplicationStatus.ERROR, "Application injoignable : " + result.errorMessage() + ".", result);
        }
        if (code != null && code < 400) {
            return new CheckOutcome(ApplicationStatus.CONNECTED, "Application joignable (HTTP " + code + ").", result);
        }
        if (code != null && (code == 401 || code == 403)) {
            return new CheckOutcome(ApplicationStatus.FAILED,
                    "Authentification refusée (" + code + ") : l'application exige une authentification.", result);
        }
        return new CheckOutcome(ApplicationStatus.FAILED, "L'application a répondu avec une erreur (HTTP " + code + ").", result);
    }

    /**
     * Connexion auto : POST reel sur l'endpoint de connexion avec les
     * identifiants fournis -> 2xx et jeton trouve : CONNECTED ; 401/403 :
     * "Identifiants refuses" ; jeton absent : "Token introuvable a <chemin>".
     */
    private CheckOutcome verifyLogin(Application application) {
        long startedAt = System.nanoTime();
        try {
            authSupport.resolve(application, timeoutSeconds);
            long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            return new CheckOutcome(ApplicationStatus.CONNECTED, "Authentification réussie (token obtenu).",
                    new HttpAvailabilityResult(true, 200, elapsed, null));
        } catch (ApplicationAuthenticationException e) {
            String path = authSupport.toSpec(application).config().tokenJsonPath();
            Integer code = e.httpStatus();
            HttpAvailabilityResult result = new HttpAvailabilityResult(code != null, code,
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis(), e.getMessage());
            return switch (e.kind()) {
                case REJECTED -> code != null && (code == 401 || code == 403)
                        ? new CheckOutcome(ApplicationStatus.FAILED, "Identifiants refusés (" + code + ").", result)
                        : new CheckOutcome(ApplicationStatus.FAILED,
                                "L'endpoint de connexion a répondu avec une erreur (HTTP " + code + ") : vérifiez le chemin.", result);
                case TOKEN_NOT_FOUND -> new CheckOutcome(ApplicationStatus.FAILED, "Token introuvable à " + path + ".", result);
                case INVALID_RESPONSE -> new CheckOutcome(ApplicationStatus.FAILED,
                        "Réponse de connexion inexploitable : " + e.getMessage() + ".", result);
                case UNREACHABLE -> new CheckOutcome(ApplicationStatus.ERROR,
                        "Endpoint de connexion injoignable : " + e.getMessage() + ".", noResponse(e.getMessage()));
            };
        }
    }

    /** Message de refus propre au type d'authentification. */
    private static String refusedMessage(ApplicationAuthType type, int code) {
        return switch (type) {
            case BEARER_TOKEN -> "Jeton refusé (" + code + ").";
            case API_KEY -> "Clé API refusée (" + code + ").";
            case BASIC -> "Identifiants Basic refusés (" + code + ").";
            default -> "Identifiants refusés (" + code + ").";
        };
    }

    /**
     * Bearer, cle API et Basic : verification reelle des identifiants, en
     * deux GET sur l'endpoint de verification :
     *   a. sans identifiants -> doit repondre 401/403 (sinon l'endpoint ne
     *      prouve rien : FAILED "Endpoint non protege") ;
     *   b. avec identifiants -> 2xx CONNECTED "Identifiants valides",
     *      401/403 FAILED (message propre au type), autre code FAILED,
     *      reseau/timeout ERROR.
     */
    private CheckOutcome verifyCredentials(Application application, ApplicationAuthSpec spec, Duration timeout) {
        String path = spec.config().verificationPath();
        if (path == null || path.isBlank()) {
            return new CheckOutcome(ApplicationStatus.FAILED,
                    "Endpoint de vérification manquant : modifiez l'application pour indiquer un endpoint protégé (ex : /api/auth/verify).",
                    noResponse(null));
        }
        String verifyUrl = UrlResolver.resolve(application.getUrl(), path);

        HttpAvailabilityResult anonymous = availabilityChecker.check(verifyUrl, timeout);
        if (!anonymous.success()) {
            return new CheckOutcome(ApplicationStatus.ERROR,
                    "Endpoint de vérification injoignable : " + anonymous.errorMessage() + ".", anonymous);
        }
        Integer anonymousCode = anonymous.httpStatus();
        if (anonymousCode != null && anonymousCode >= 200 && anonymousCode < 300) {
            return new CheckOutcome(ApplicationStatus.FAILED,
                    "Endpoint non protégé : choisissez un endpoint qui exige l'authentification.", anonymous);
        }
        if (anonymousCode == null || (anonymousCode != 401 && anonymousCode != 403)) {
            return new CheckOutcome(ApplicationStatus.FAILED,
                    "L'endpoint de vérification a répondu HTTP " + anonymousCode
                            + " sans identifiants (attendu 401 ou 403) : vérifiez le chemin.", anonymous);
        }

        ResolvedAuth auth = authSupport.resolve(application, timeoutSeconds);
        HttpAvailabilityResult authenticated = availabilityChecker.check(auth.applyToUrl(verifyUrl), auth.headers(), timeout);
        Integer code = authenticated.httpStatus();
        if (!authenticated.success()) {
            return new CheckOutcome(ApplicationStatus.ERROR,
                    "Endpoint de vérification injoignable : " + authenticated.errorMessage() + ".", authenticated);
        }
        if (code != null && code >= 200 && code < 300) {
            return new CheckOutcome(ApplicationStatus.CONNECTED, "Identifiants valides (HTTP " + code + ").", authenticated);
        }
        if (code != null && (code == 401 || code == 403)) {
            return new CheckOutcome(ApplicationStatus.FAILED, refusedMessage(spec.type(), code), authenticated);
        }
        return new CheckOutcome(ApplicationStatus.FAILED,
                "L'endpoint de vérification a répondu avec une erreur (HTTP " + code + ").", authenticated);
    }

    /** Enregistre le resultat (status, message, date), trace l'audit et
     * construit la reponse. L'audit SUCCESS reflete que l'OPERATION de test
     * a ete executee, pas que l'application est joignable (voir Phase 12). */
    private ApplicationTestResponse finishTest(Application application, CheckOutcome outcome) {
        String message = outcome.message();
        application.setStatus(outcome.status());
        application.setStatusMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
        application.setStatusCheckedAt(Instant.now());
        applicationRepository.save(application);
        auditLogService.record(AuditAction.TEST, AuditModule.APPLICATION, AuditResult.SUCCESS,
                "Application availability tested: " + application.getName() + " -> " + outcome.status());
        return new ApplicationTestResponse(outcome.status(), outcome.result().httpStatus(),
                outcome.result().responseTimeMs(), message);
    }

    private Application findOrThrow(UUID id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application introuvable : " + id));
    }
}
