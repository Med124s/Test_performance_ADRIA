package com.loadpilot.backend.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.loadpilot.backend.enums.HttpMethod;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Test UNITAIRE pur (Jakarta Bean Validation directement, aucun contexte
 * Spring/HTTP) des contraintes @Valid des DTO de requete - complement aux
 * tests 400 deja realises via MockMvc dans les *ControllerTest (Phase 6-8),
 * qui ne verifient qu'un sous-ensemble de cas via l'API HTTP. Ici, chaque
 * contrainte annoncee dans le DTO est verifiee individuellement et de
 * maniere isolee/rapide.
 */
class RequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    // ------------------------------------------------------------
    // ApplicationRequest
    // ------------------------------------------------------------

    @Test
    void applicationRequest_valid_hasNoViolations() {
        ApplicationRequest request = new ApplicationRequest("My App", "desc", "http://localhost:8080");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void applicationRequest_blankName_isRejected() {
        ApplicationRequest request = new ApplicationRequest(" ", "desc", "http://localhost:8080");

        Set<ConstraintViolation<ApplicationRequest>> violations = validator.validate(request);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void applicationRequest_nullName_isRejected() {
        ApplicationRequest request = new ApplicationRequest(null, "desc", "http://localhost:8080");

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void applicationRequest_blankUrl_isRejected() {
        ApplicationRequest request = new ApplicationRequest("App", "desc", "");

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("url"));
    }

    @Test
    void applicationRequest_invalidUrl_isRejected() {
        ApplicationRequest request = new ApplicationRequest("App", "desc", "not-a-url");

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("url"));
    }

    @Test
    void applicationRequest_nameTooLong_isRejected() {
        ApplicationRequest request = new ApplicationRequest("x".repeat(256), "desc", "http://localhost:8080");

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    // ------------------------------------------------------------
    // ScenarioRequest
    // ------------------------------------------------------------

    @Test
    void scenarioRequest_valid_hasNoViolations() {
        ScenarioRequest request = new ScenarioRequest(UUID.randomUUID(), "Scenario", "desc");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void scenarioRequest_nullApplicationId_isRejected() {
        ScenarioRequest request = new ScenarioRequest(null, "Scenario", "desc");

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("applicationId"));
    }

    @Test
    void scenarioRequest_blankName_isRejected() {
        ScenarioRequest request = new ScenarioRequest(UUID.randomUUID(), "", "desc");

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void scenarioRequest_descriptionTooLong_isRejected() {
        ScenarioRequest request = new ScenarioRequest(UUID.randomUUID(), "Scenario", "x".repeat(1001));

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("description"));
    }

    @Test
    void scenarioRequest_nullDescription_isAllowed() {
        ScenarioRequest request = new ScenarioRequest(UUID.randomUUID(), "Scenario", null);

        assertThat(validator.validate(request)).isEmpty();
    }

    // ------------------------------------------------------------
    // StepRequest
    // ------------------------------------------------------------

    @Test
    void stepRequest_valid_hasNoViolations() {
        StepRequest request = new StepRequest(UUID.randomUUID(), "Step", HttpMethod.GET, "/api/x", null, null, 1, 200,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void stepRequest_nullScenarioId_isRejected() {
        StepRequest request = new StepRequest(null, "Step", HttpMethod.GET, "/api/x", null, null, 1, 200,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("scenarioId"));
    }

    @Test
    void stepRequest_nullMethod_isRejected() {
        StepRequest request = new StepRequest(UUID.randomUUID(), "Step", null, "/api/x", null, null, 1, 200,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("method"));
    }

    @Test
    void stepRequest_urlWithSpaces_isRejected() {
        StepRequest request = new StepRequest(UUID.randomUUID(), "Step", HttpMethod.GET, "/api/ with space", null, null, 1, 200,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("url"));
    }

    @Test
    void stepRequest_relativeUrlWithoutSpaces_isAllowed() {
        // Volontairement PAS @URL (voir StepRequest) : un chemin relatif est legitime.
        StepRequest request = new StepRequest(UUID.randomUUID(), "Step", HttpMethod.GET, "/api/auth/login", null, null, 1, 200,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void stepRequest_orderZero_isRejected() {
        StepRequest request = new StepRequest(UUID.randomUUID(), "Step", HttpMethod.GET, "/x", null, null, 0, 200,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("order"));
    }

    @Test
    void stepRequest_negativeOrder_isRejected() {
        StepRequest request = new StepRequest(UUID.randomUUID(), "Step", HttpMethod.GET, "/x", null, null, -1, 200,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("order"));
    }

    @Test
    void stepRequest_nullOrder_isRejected() {
        StepRequest request = new StepRequest(UUID.randomUUID(), "Step", HttpMethod.GET, "/x", null, null, null, 200,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("order"));
    }

    @Test
    void stepRequest_expectedStatusBelow100_isRejected() {
        StepRequest request = new StepRequest(UUID.randomUUID(), "Step", HttpMethod.GET, "/x", null, null, 1, 99,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("expectedStatus"));
    }

    @Test
    void stepRequest_expectedStatusAbove599_isRejected() {
        StepRequest request = new StepRequest(UUID.randomUUID(), "Step", HttpMethod.GET, "/x", null, null, 1, 600,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("expectedStatus"));
    }

    @Test
    void stepRequest_nullExpectedStatus_isAllowed() {
        // Optionnel (voir StepRequest/HttpClientExecutionEngine : sans lui,
        // le succes se base sur 200<=status<400).
        StepRequest request = new StepRequest(UUID.randomUUID(), "Step", HttpMethod.GET, "/x", null, null, 1, null,
                null, null, null, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }
}
