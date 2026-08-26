package com.complyance.assignment.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@SpringBootTest(classes = SafeRetryApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StarterSmokeTest {

    private static final String ALPHA_AUTH = "Bearer tenant-alpha-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void flywayCreatesAndSeedsTheStarterDatabase() {
        assertThat(jdbc.queryForObject("select count(*) from tasks", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isZero();
    }

    @Test
    void authenticatedTenantCanListAndReadOnlyItsTasks() throws Exception {
        mockMvc.perform(get("/api/tasks").header("Authorization", ALPHA_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(3))
                .andExpect(jsonPath("$.tasks[0].id").value("task-alpha-permanent"))
                .andExpect(jsonPath("$.tasks[0].workflowId").value("workflow-alpha"))
                .andExpect(jsonPath("$.tasks[0].title").isNotEmpty())
                .andExpect(jsonPath("$.tasks[0].status").isNotEmpty())
                .andExpect(jsonPath("$.tasks[0].version").isNumber());

        mockMvc.perform(get("/api/tasks/task-alpha-retryable").header("Authorization", ALPHA_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-alpha-retryable"));

        mockMvc.perform(get("/api/tasks/task-beta-retryable").header("Authorization", ALPHA_AUTH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void apiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /**
     * Supplied descriptor, updated for the implemented endpoint.
     *
     * <p>The starter asserted a stable {@code 501 NOT_IMPLEMENTED} with no writes. That
     * assertion is superseded by the published API contract, which requires {@code 202}
     * on the first accepted request, so it cannot coexist with a finished implementation.
     * Its actual intent — the endpoint never leaves a partial change set behind — is
     * preserved and strengthened here: the accepted path writes exactly one attempt, one
     * audit event, and one outbox record. This is the only supplied assertion changed;
     * every other descriptor in the package is enabled with its assertions untouched.
     */
    @Test
    void implementedRetryEndpointAcceptsOnceAndWritesExactlyOneCompleteChangeSet() throws Exception {
        mockMvc.perform(post("/api/workflows/workflow-alpha/tasks/task-alpha-retryable/retry")
                        .header("Authorization", ALPHA_AUTH)
                        .header("Idempotency-Key", "starter-smoke-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RETRY_QUEUED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        assertThat(jdbc.queryForObject("select status from tasks where id = 'task-alpha-retryable'", String.class))
                .isEqualTo("RETRY_QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from outbox_messages", Long.class)).isOne();
    }

    @Test
    void readinessProbeIsAvailableWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
