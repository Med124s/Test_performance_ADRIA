package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.request.ExecutionLimitsRequest;
import com.loadpilot.backend.dto.response.ExecutionLimitsResponse;
import com.loadpilot.backend.enums.AuditAction;
import com.loadpilot.backend.enums.AuditModule;
import com.loadpilot.backend.exception.BadRequestException;
import com.loadpilot.backend.service.audit.AuditEvent;
import com.loadpilot.backend.service.audit.AuditTargetType;
import com.loadpilot.backend.service.validation.LoadLimits;
import com.loadpilot.backend.service.validation.LoadRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Limites de charge sur deux niveaux :
 *   - plafond technique : variables du serveur (LOADPILOT_MAX_*), jamais modifiables depuis l'interface ;
 *   - limite applicative : reglee dans Parametres > Systeme > Limites par le Super administrateur,
 *     toujours <= plafond (par defaut = plafond).
 * Lues a chaque enregistrement, lancement et declenchement de planification : effet immediat, sans
 * effet sur les executions deja en cours. Chaque changement est journalise (avant -> apres).
 */
@Service
public class ExecutionLimitsService {

    /** Une limite : cle API, reglage, libelle, unite affichee. */
    public enum Limit {
        VIRTUAL_USERS_PER_TEST("virtualUsersPerTest", "limits.virtual-users-per-test", "Utilisateurs virtuels par test", "utilisateurs"),
        VIRTUAL_USERS_TOTAL("virtualUsersTotal", "limits.virtual-users-total", "Utilisateurs virtuels au total", "utilisateurs"),
        CONCURRENT_TESTS("concurrentTests", "limits.concurrent-tests", "Tests en même temps", "tests"),
        MAX_DURATION_SECONDS("maxDurationSeconds", "limits.max-duration-seconds", "Durée maximale d’un test", "s"),
        MAX_ITERATIONS("maxIterations", "limits.max-iterations", "Itérations maximales par utilisateur", "itérations"),
        MAX_TARGET_RPS("maxTargetRps", "limits.max-target-rps", "Débit cible maximal", "req/s");

        public final String apiKey;
        public final String settingKey;
        public final String label;
        public final String unit;

        Limit(String apiKey, String settingKey, String label, String unit) {
            this.apiKey = apiKey;
            this.settingKey = settingKey;
            this.label = label;
            this.unit = unit;
        }
    }

    private final AppSettingsService settings;
    private final AuditLogService auditLogService;
    private final Map<Limit, Integer> ceilings = new LinkedHashMap<>();
    private final int executionTimeoutSeconds;
    private final int availabilityTimeoutSeconds;

    public ExecutionLimitsService(AppSettingsService settings, AuditLogService auditLogService,
                                  @Value("${app.execution.max-virtual-users-per-execution}") int maxVirtualUsersPerExecution,
                                  @Value("${app.execution.max-global-virtual-users}") int maxGlobalVirtualUsers,
                                  @Value("${app.execution.max-concurrent-executions}") int maxConcurrentExecutions,
                                  @Value("${app.execution.max-duration-seconds:3600}") int maxDurationSeconds,
                                  @Value("${app.execution.max-iterations:10000}") int maxIterations,
                                  @Value("${app.execution.max-target-rps:500}") int maxTargetRps,
                                  @Value("${app.execution.timeout-seconds}") int executionTimeoutSeconds,
                                  @Value("${app.availability.timeout-seconds}") int availabilityTimeoutSeconds) {
        this.settings = settings;
        this.auditLogService = auditLogService;
        // Plafonds incoherents (variables d'environnement d'avant, ex. 500 par test / 200 au total) : le
        // plafond par test est ramene au plafond global - jamais affiche ni applique au-dessus.
        if (maxVirtualUsersPerExecution > maxGlobalVirtualUsers) {
            org.slf4j.LoggerFactory.getLogger(ExecutionLimitsService.class).warn(
                    "LOADPILOT_MAX_VUS_PER_EXECUTION ({}) superieur a LOADPILOT_MAX_GLOBAL_VUS ({}) : plafond par test ramene a {}.",
                    maxVirtualUsersPerExecution, maxGlobalVirtualUsers, maxGlobalVirtualUsers);
        }
        ceilings.put(Limit.VIRTUAL_USERS_PER_TEST, Math.min(maxVirtualUsersPerExecution, maxGlobalVirtualUsers));
        ceilings.put(Limit.VIRTUAL_USERS_TOTAL, maxGlobalVirtualUsers);
        ceilings.put(Limit.CONCURRENT_TESTS, maxConcurrentExecutions);
        ceilings.put(Limit.MAX_DURATION_SECONDS, maxDurationSeconds);
        ceilings.put(Limit.MAX_ITERATIONS, maxIterations);
        ceilings.put(Limit.MAX_TARGET_RPS, maxTargetRps);
        this.executionTimeoutSeconds = executionTimeoutSeconds;
        this.availabilityTimeoutSeconds = availabilityTimeoutSeconds;
    }

    public int ceiling(Limit limit) {
        return ceilings.get(limit);
    }

    /**
     * Limite applicative en vigueur : reglee dans Parametres, jamais au-dessus du plafond (defaut = plafond).
     * « Par test » n'est jamais au-dessus de « au total » : une ancienne valeur enregistree plus haute (ou un
     * plafond du serveur baisse depuis) ne cree donc jamais de configuration incoherente.
     */
    public int value(Limit limit) {
        if (limit == Limit.VIRTUAL_USERS_PER_TEST) {
            return Math.min(storedValue(limit), value(Limit.VIRTUAL_USERS_TOTAL));
        }
        return storedValue(limit);
    }

    /** Valeur enregistree (bornee par le plafond), avant la regle « par test <= au total ». */
    private int storedValue(Limit limit) {
        int ceiling = ceiling(limit);
        int configured = settings.getInt(limit.settingKey, ceiling);
        return Math.max(1, Math.min(configured, ceiling));
    }

    /** Utilisateurs reellement possibles pour un test (par test, jamais au-dessus du total). */
    public int maxVirtualUsersPerTest() {
        return value(Limit.VIRTUAL_USERS_PER_TEST);
    }

    public LoadLimits loadLimits() {
        return new LoadLimits(maxVirtualUsersPerTest(), value(Limit.MAX_DURATION_SECONDS), value(Limit.MAX_ITERATIONS), value(Limit.MAX_TARGET_RPS),
                targetRpsAdvisedAboveVirtualUsers);
    }

    /** Au-dela, un debit cible est fortement conseille (avertissement, voir LoadRules.warnings) ; 0 = desactive. */
    @Value("${app.execution.target-rps-advised-above-virtual-users:" + LoadLimits.DEFAULT_TARGET_RPS_ADVISED_ABOVE_VIRTUAL_USERS + "}")
    private int targetRpsAdvisedAboveVirtualUsers;

    public ExecutionLimitsResponse get() {
        List<ExecutionLimitsResponse.Limit> list = new ArrayList<>();
        for (Limit l : Limit.values()) {
            list.add(new ExecutionLimitsResponse.Limit(l.apiKey, l.label, l.unit, value(l), ceiling(l)));
        }
        return new ExecutionLimitsResponse(list, executionTimeoutSeconds, availabilityTimeoutSeconds, maxVirtualUsersPerTest(), List.of());
    }

    /** Message de refus : « au total » en dessous de « par test » (meme texte que l'interface). */
    public static final String TOTAL_BELOW_PER_TEST = "Le total doit être supérieur ou égal au maximum par test.";

    /**
     * Enregistre les limites. « Au total » strictement inferieur a « par test » : toujours refuse (400),
     * rien n'est enregistre - que l'administrateur ait baisse le total ou monte « par test ». Il corrige
     * lui-meme l'un des deux (aucun ajustement automatique).
     */
    public ExecutionLimitsResponse update(ExecutionLimitsRequest request, String updatedBy) {
        Map<Limit, Integer> requested = new LinkedHashMap<>();
        requested.put(Limit.VIRTUAL_USERS_PER_TEST, request.virtualUsersPerTest());
        requested.put(Limit.VIRTUAL_USERS_TOTAL, request.virtualUsersTotal());
        requested.put(Limit.CONCURRENT_TESTS, request.concurrentTests());
        requested.put(Limit.MAX_DURATION_SECONDS, request.maxDurationSeconds());
        requested.put(Limit.MAX_ITERATIONS, request.maxIterations());
        requested.put(Limit.MAX_TARGET_RPS, request.maxTargetRps());
        List<String> errors = new ArrayList<>();
        requested.forEach((l, v) -> {
            String problem = problem(l, v, ceiling(l));
            if (problem != null) errors.add(problem);
        });
        if (!errors.isEmpty()) throw new BadRequestException(String.join(" ", errors));
        // Un seul test ne doit jamais pouvoir depasser le plafond de tous les tests reunis.
        if (request.virtualUsersTotal() < request.virtualUsersPerTest()) throw new BadRequestException(TOTAL_BELOW_PER_TEST);

        // Avant : valeurs enregistrees (une ancienne incoherence apparait telle quelle au journal).
        Map<Limit, Integer> before = new LinkedHashMap<>();
        for (Limit l : Limit.values()) before.put(l, storedValue(l));

        requested.forEach((l, v) -> settings.set(l.settingKey, String.valueOf(v), updatedBy));
        AuditEvent event = AuditEvent.of(AuditAction.UPDATE, AuditModule.SETTINGS)
                .target(AuditTargetType.SETTINGS, "limits", "Limites du serveur")
                .description("Limites des tests modifiées (effet sur les prochains enregistrements, lancements et planifications)");
        for (Limit l : Limit.values()) {
            event.change(l.label, display(l, before.get(l)), display(l, storedValue(l)));
        }
        auditLogService.record(event);
        return get();
    }

    /** Message identique a l'interface (null si correct). */
    public static String problem(Limit limit, Integer value, int ceiling) {
        if (value == null) return limit.label + " : valeur obligatoire.";
        if (value < 1) return limit.label + " : au moins 1.";
        if (value > ceiling) return limit.label + " : maximum " + LoadRules.number(ceiling) + " (plafond du serveur).";
        return null;
    }

    private static String display(Limit limit, int value) {
        return LoadRules.number(value) + " " + limit.unit;
    }
}
