package com.complyance.assignment.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.complyance.assignment.SafeRetryApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract tests for the emergency tenant retry pause.
 *
 * <p>Covers the published behaviour of {@code PUT /api/retries/pause}: idempotent {@code 204},
 * new retries rejected with {@code 409 TENANT_RETRIES_PAUSED} and no writes, pre-pause
 * replays still returning their original result, and other tenants unaffected. The
 * "survives a restart" and cross-instance requirements are met by storing the pause as a
 * database row; durability is exercised here by reading it back through a fresh request.
 */
@SpringBootTest(classes = SafeRetryApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TenantRetryPauseContractTest {

    private static final String ALPHA_AUTH = "Bearer tenant-alpha-token";
    private static final String BETA_AUTH = "Bearer tenant-beta-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void pauseRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/retries/pause"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void pauseReturns204AndIsIdempotent() throws Exception {
        pause(ALPHA_AUTH).andExpect(status().isNoContent());
        // Calling it again for an already-paused tenant still succeeds with 204.
        pause(ALPHA_AUTH).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                        "select paused from tenant_retry_pause where tenant_id = 'tenant-alpha'", Boolean.class))
                .isTrue();
    }

    @Test
    void newRetryForAPausedTenantIsRejectedWithoutAnyWrite() throws Exception {
        pause(ALPHA_AUTH).andExpect(status().isNoContent());

        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "paused-tenant-key-1", "0")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_RETRIES_PAUSED"));

        assertNoWrites();
        assertThat(jdbc.queryForObject(
                        "select status from tasks where id = 'task-alpha-retryable'", String.class))
                .isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject(
                        "select version from tasks where id = 'task-alpha-retryable'", Long.class))
                .isZero();
    }

    @Test
    void pauseRejectsRetriesForAnyWorkflowOrTaskOfTheTenant() throws Exception {
        pause(ALPHA_AUTH).andExpect(status().isNoContent());

        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "paused-any-1", "0")
                .andExpect(jsonPath("$.code").value("TENANT_RETRIES_PAUSED"));
        // A different task under the same tenant is paused too.
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-permanent", "paused-any-2", "0")
                .andExpect(jsonPath("$.code").value("TENANT_RETRIES_PAUSED"));
        assertNoWrites();
    }

    @Test
    void replaysOfRetriesAcceptedBeforeThePauseStillReturnTheirOriginalResult() throws Exception {
        // Accept a retry first, capturing the attempt id.
        var accepted = retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "pre-pause-replay-key", "0")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        var attemptId = com.jayway.jsonpath.JsonPath.read(
                accepted.getResponse().getContentAsString(), "$.attemptId");

        pause(ALPHA_AUTH).andExpect(status().isNoContent());

        // The exact same request replays the original accepted result, pause notwithstanding.
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "pre-pause-replay-key", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.attemptId").value(attemptId))
                .andExpect(jsonPath("$.version").value(1));

        assertRowCounts(1);
    }

    @Test
    void pausingOneTenantLeavesOtherTenantsWorking() throws Exception {
        pause(ALPHA_AUTH).andExpect(status().isNoContent());

        // Beta is untouched and can still queue a retry.
        retry(BETA_AUTH, "workflow-beta", "task-beta-retryable", "other-tenant-works-key", "0")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RETRY_QUEUED"));

        // Alpha remains paused.
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "still-paused-key", "0")
                .andExpect(jsonPath("$.code").value("TENANT_RETRIES_PAUSED"));

        assertThat(jdbc.queryForObject(
                        "select count(*) from retry_attempts where tenant_id = 'tenant-beta'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from retry_attempts where tenant_id = 'tenant-alpha'", Long.class))
                .isZero();
    }

    private ResultActions pause(String authorization) throws Exception {
        return mockMvc.perform(put("/api/retries/pause").header("Authorization", authorization));
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
    }

    private void assertRowCounts(long expected) {
        assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from outbox_messages", Long.class)).isEqualTo(expected);
    }
}
