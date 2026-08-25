package com.complyance.assignment.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.complyance.assignment.SafeRetryApplication;
import com.complyance.assignment.retry.application.RetryFailureInjector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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

/**
 * Published behavioral contracts. Enable these tests progressively while implementing the assignment.
 * They intentionally do not prescribe a locking strategy or transaction design.
 */
@SpringBootTest(classes = SafeRetryApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PublicContractTest {

    private static final String ALPHA_AUTH = "Bearer tenant-alpha-token";
    private static final String BETA_AUTH = "Bearer tenant-beta-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private RetryFailureInjector failureInjector;

    @Test
    void acceptedRetryReturns202AndCreatesOneAttemptAuditAndOutboxRow() throws Exception {
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "accepted-contract-key", 0)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RETRY_QUEUED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.attemptId").isNotEmpty());

        assertAtomicRowCounts(1);
    }

    @Test
    void sameKeyAndRequestReturns200WithOriginalAttemptAndNoDuplicateRows() throws Exception {
        var first = retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "replay-contract-key", 0)
                .andExpect(status().isAccepted())
                .andReturn();
        var second = retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "replay-contract-key", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .contains(extractAttemptId(first.getResponse().getContentAsString()));
        assertAtomicRowCounts(1);
    }

    @Test
    void sameKeyWithDifferentExpectedVersionReturns409() throws Exception {
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "mismatch-contract-key", 0)
                .andExpect(status().isAccepted());
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "mismatch-contract-key", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void staleExpectedVersionReturns409WithoutWrites() throws Exception {
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "stale-contract-key", 9)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_TASK_VERSION"));
        assertAtomicRowCounts(0);
    }

    @Test
    void crossTenantRetryIsConcealedAs404WithoutWrites() throws Exception {
        retry(BETA_AUTH, "workflow-alpha", "task-alpha-retryable", "tenant-contract-key", 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        assertAtomicRowCounts(0);
    }

    @Test
    void concurrentSameKeyRequestsProduceOneAttemptAndOneReplay() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrentRetry(ready, start, "concurrent-contract-key"));
            var second = pool.submit(() -> concurrentRetry(ready, start, "concurrent-contract-key"));
            ready.await();
            start.countDown();

            assertThat(first.get().getResponse().getStatus())
                    .isIn(200, 202);
            assertThat(second.get().getResponse().getStatus())
                    .isIn(200, 202);
            assertThat(first.get().getResponse().getStatus())
                    .isNotEqualTo(second.get().getResponse().getStatus());
        }
        assertAtomicRowCounts(1);
    }

    @Test
    void failureAfterOutboxInsertionRollsBackTheEntireChangeSet() throws Exception {
        doThrow(new IllegalStateException("injected failure"))
                .when(failureInjector)
                .afterOutboxInserted();

        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "rollback-contract-key", 0)
                .andExpect(status().is5xxServerError());

        assertThat(jdbc.queryForObject(
                        "select status from tasks where id = 'task-alpha-retryable'", String.class))
                .isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject(
                        "select version from tasks where id = 'task-alpha-retryable'", Long.class))
                .isZero();
        assertAtomicRowCounts(0);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records", Long.class)).isZero();
    }

    @Test
    void workflowMismatchIsConcealedAs404WithoutWrites() throws Exception {
        retry(ALPHA_AUTH, "workflow-beta", "task-alpha-retryable", "workflow-mismatch-key", 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        assertAtomicRowCounts(0);
    }

    @Test
    void nonRetryableTaskReturns409WithoutWrites() throws Exception {
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-permanent", "permanent-contract-key", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_RETRYABLE"));
        assertAtomicRowCounts(0);
    }

    @Test
    void invalidIdempotencyKeyReturns400WithoutWrites() throws Exception {
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "short", 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertAtomicRowCounts(0);
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/workflows/workflow-alpha/tasks/task-alpha-retryable/retry")
                        .header("Authorization", ALPHA_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void sameKeyWithDifferentTaskIdReturns409EvenWhenTheNewTaskIsMissing() throws Exception {
        retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", "bound-contract-key", 0)
                .andExpect(status().isAccepted());
        retry(ALPHA_AUTH, "workflow-alpha", "task-does-not-exist", "bound-contract-key", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertAtomicRowCounts(1);
    }

    @Test
    void concurrentDifferentKeysProduceOneAcceptAndOneConflict() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrentRetry(ready, start, "different-key-one"));
            var second = pool.submit(() -> concurrentRetry(ready, start, "different-key-two"));
            ready.await();
            start.countDown();

            var statuses = java.util.List.of(
                    first.get().getResponse().getStatus(), second.get().getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        }
        assertAtomicRowCounts(1);
    }

    private org.springframework.test.web.servlet.ResultActions retry(
            String authorization, String workflowId, String taskId, String key, long expectedVersion)
            throws Exception {
        return mockMvc.perform(post("/api/workflows/" + workflowId + "/tasks/" + taskId + "/retry")
                .header("Authorization", authorization)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + expectedVersion + "}"));
    }

    private org.springframework.test.web.servlet.MvcResult concurrentRetry(
            CountDownLatch ready, CountDownLatch start, String key) throws Exception {
        ready.countDown();
        start.await();
        return retry(ALPHA_AUTH, "workflow-alpha", "task-alpha-retryable", key, 0).andReturn();
    }

    private void assertAtomicRowCounts(long expected) {
        assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from outbox_messages", Long.class)).isEqualTo(expected);
    }

    private String extractAttemptId(String json) {
        var marker = "\"attemptId\":\"";
        var start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
