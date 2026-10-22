package com.loadpilot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loadpilot.backend.dto.response.ExecutionLimitsResponse;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Plafonds du serveur affiches sous chaque champ (« Maximum X (plafond du serveur) ») : chaque limite porte
 * SON plafond, et le plafond par test n'est jamais au-dessus du plafond global, meme avec des variables
 * d'environnement incoherentes (ancienne configuration 500 par test / 200 au total).
 */
class ExecutionLimitsCeilingsTest {

    private static ExecutionLimitsService service(int perTestCeiling, int totalCeiling) {
        AppSettingsService settings = mock(AppSettingsService.class);
        // Aucune limite reglee dans Parametres : limite applicative = plafond.
        when(settings.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        return new ExecutionLimitsService(settings, mock(AuditLogService.class), perTestCeiling, totalCeiling, 10, 3600, 10000, 500, 10, 5);
    }

    private static Map<String, int[]> byKey(ExecutionLimitsResponse response) {
        return response.limits().stream().collect(Collectors.toMap(ExecutionLimitsResponse.Limit::key, l -> new int[] {l.value(), l.ceiling()}));
    }

    @Test
    void defaults_eachLimitCarriesItsOwnCeiling() {
        Map<String, int[]> limits = byKey(service(200, 500).get());
        assertThat(limits.get("virtualUsersPerTest")).as("par test : valeur, plafond").containsExactly(200, 200);
        assertThat(limits.get("virtualUsersTotal")).as("au total : valeur, plafond").containsExactly(500, 500);
    }

    @Test
    void incoherentEnvironmentCeilings_perTestCeilingCappedToTheGlobalOne() {
        ExecutionLimitsService limits = service(500, 200);
        Map<String, int[]> byKey = byKey(limits.get());
        assertThat(byKey.get("virtualUsersPerTest")).as("jamais « Maximum 500 » sous par test").containsExactly(200, 200);
        assertThat(byKey.get("virtualUsersTotal")).containsExactly(200, 200);
        assertThat(limits.ceiling(ExecutionLimitsService.Limit.VIRTUAL_USERS_PER_TEST)).isEqualTo(200);
        assertThat(limits.maxVirtualUsersPerTest()).isEqualTo(200);
    }
}
