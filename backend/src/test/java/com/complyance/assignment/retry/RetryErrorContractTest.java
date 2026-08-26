package com.complyance.assignment.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.complyance.assignment.SafeRetryApplication;
import com.complyance.assignment.retry.application.RetryFailureInjector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Solution tests for the published error taxonomy and the non-enumeration rules that
 * the supplied descriptors do not cover.
 */
@SpringBootTest(classes = SafeRetryApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class RetryErrorContractTest {

    private static final String ALPHA_AUTH = "Bearer tenant-alpha-token";
    private static final String BETA_AUTH = "Bearer tenant-beta-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private RetryFailureInjector failureInjector;

    @Test
    void missingAuthenticationReturns401AndNeverReachesTheDatabase() throws Exception {
        mockMvc.perform(post("/api/workflows/workflow-alpha/tasks/task-alpha-retryable/retry")
                        .header("Idempotency-Key", "unauthenticated-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertNoWrites();
    }

    @Test
    void missingIdempotencyKeyHeaderReturns400() throws Exception {
        mockMvc.perform(post("/api/workflows/workflow-alpha/tasks/task-alpha-retryable/retry")
                        .header("Authorization", ALPHA_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertNoWrites();
    }

    @Test
    void idempotencyKeysOutsideThePublishedGrammarReturn400() throws Exception {
        for (var invalidKey : new String[] {
            "short7",                       // 7 characters, one below the minimum
            "-leading-punctuation",         // first character is not alphanumeric
            "has spaces in it",             // space is outside the published alphabet
            "unicode-kéy-value",            // non-ASCII
            "x".repeat(121)                 // 121 characters, one above the maximum
        }) {
            retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", invalidKey, "0")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        // The published boundaries themselves are accepted.
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "a".repeat(8), "0")
                .andExpect(status().isAccepted());
        assertRowCounts(1);
    }

    @Test
    void malformedOrMissingExpectedVersionReturns400() throws Exception {
        for (var body : new String[] {
            "{\"expectedVersion\":",          // truncated JSON
            "{}",                             // absent field
            "{\"expectedVersion\":null}",
            "{\"expectedVersion\":-1}",       // negative version
            "{\"expectedVersion\":\"zero\"}"  // wrong type
        }) {
            mockMvc.perform(post("/api/workflows/workflow-alpha/tasks/task-alpha-retryable/retry")
                            .header("Authorization", ALPHA_AUTH)
                            .header("Idempotency-Key", "body-validation-key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertNoWrites();
    }

    @Test
    void workflowMismatchIsConcealedAs404() throws Exception {
        retry(ALPHA_AUTH, "workflow-beta", "task-alpha-retryable", "workflow-mismatch-key", "0")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        assertNoWrites();
    }

    @Test
    void missingTaskAndAnotherTenantsTaskAreIndistinguishable() throws Exception {
        var absent = retry(ALPHA_AUTH, "workflow-alpha", "task-does-not-exist", "absent-task-key-1", "0")
                .andExpect(status().isNotFound())
                .andReturn();
        var otherTenant = retry(ALPHA_AUTH, "workflow-beta", "task-beta-retryable", "other-tenant-key-1", "0")
                .andExpect(status().isNotFound())
                .andReturn();

        // Byte-identical bodies: a caller cannot tell "no such task" from "not your task".
        assertThat(otherTenant.getResponse().getContentAsString())
                .isEqualTo(absent.getResponse().getContentAsString());
        assertNoWrites();
    }

    @Test
    void nonRetryableTaskReturns409WithoutWrites() throws Exception {
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-permanent", "permanent-task-key-1", "0")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_RETRYABLE"));
        assertNoWrites();
    }

    @Test
    void reusedKeyLosesEvenWhenTheNewTargetWouldOtherwiseBeMissing() throws Exception {
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "reuse-vs-404-key", "0")
                .andExpect(status().isAccepted());

        // A 404 target would win if the key were checked after the task lookup.
        retry(ALPHA_AUTH, "workflow-alpha", "task-does-not-exist", "reuse-vs-404-key", "0")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertRowCounts(1);
    }

    @Test
    void eachFingerprintFieldIsPartOfTheReuseCheck() throws Exception {
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "fingerprint-field-key", "0")
                .andExpect(status().isAccepted());

        // Different workflow, different task, and different expected version each conflict.
        retry(ALPHA_AUTH, "workflow-beta", "task-alpha-retryable", "fingerprint-field-key", "0")
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-permanent", "fingerprint-field-key", "0")
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "fingerprint-field-key", "1")
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertRowCounts(1);
    }

    @Test
    void anIdempotencyKeyIsScopedToItsTenantNotGlobally() throws Exception {
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "shared-key-across-tenants", "0")
                .andExpect(status().isAccepted());

        // The other tenant reuses the same string against its own task and is accepted.
        retry(BETA_AUTH, "workflow-beta", "task-beta-retryable", "shared-key-across-tenants", "0")
                .andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject(
                        "select count(distinct tenant_id) from retry_attempts", Long.class))
                .isEqualTo(2);
        assertRowCounts(2);
    }

    @Test
    void aRolledBackTransactionLeavesNoIdempotencyResultAndTheKeyStaysUsable() throws Exception {
        doThrow(new IllegalStateException("injected failure"))
                .when(failureInjector)
                .afterOutboxInserted();

        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "rollback-then-accept-key", "0")
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertNoWrites();

        // Same key, same fingerprint: because the first transaction rolled back there is no
        // idempotency result, so this is a first acceptance rather than a replay.
        org.mockito.Mockito.reset(failureInjector);
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "rollback-then-accept-key", "0")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replayed").value(false));
        assertRowCounts(1);
    }

    @Test
    void errorBodiesCarryOnlyThePublishedFieldsAndLeakNothing() throws Exception {
        var body = retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "stale-leak-check-key", "7")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("STALE_TASK_VERSION"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(3)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("tenant-alpha")
                .doesNotContain("tenant-beta")
                .doesNotContain("tenant-alpha-token")
                .doesNotContainIgnoringCase("select ")
                .doesNotContainIgnoringCase("exception")
                .doesNotContain("com.complyance");
    }

    private ResultActions retry(
            String authorization, String workflowId, String taskId, String key, String expectedVersion)
            throws Exception {
        return mockMvc.perform(post("/api/workflows/" + workflowId + "/tasks/" + taskId + "/retry")
                .header("Authorization", authorization)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + expectedVersion + "}"));
    }

    private void assertNoWrites() {
        assertRowCounts(0);
        assertThat(jdbc.queryForObject(
                        "select status from tasks where id = 'task-alpha-retryable'", String.class))
                .isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject(
                        "select version from tasks where id = 'task-alpha-retryable'", Long.class))
                .isZero();
    }

    private void assertRowCounts(long expected) {
        assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from outbox_messages", Long.class)).isEqualTo(expected);
    }
}
