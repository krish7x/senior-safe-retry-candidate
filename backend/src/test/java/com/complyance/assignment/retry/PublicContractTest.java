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
import org.junit.jupiter.api.Disabled;
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
    @Disabled("Contract: enable when a retry can be accepted atomically")
    void acceptedRetryReturns202AndCreatesOneAttemptAuditAndOutboxRow() throws Exception {
        retry(ALPHA_AUTH, "task-alpha-retryable", "accepted-contract-key", 0)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RETRY_QUEUED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.attemptId").isNotEmpty());

        assertAtomicRowCounts(1);
    }

    @Test
    @Disabled("Contract: enable when exact idempotent replay is implemented")
    void sameKeyAndRequestReturns200WithOriginalAttemptAndNoDuplicateRows() throws Exception {
        var first = retry(ALPHA_AUTH, "task-alpha-retryable", "replay-contract-key", 0)
                .andExpect(status().isAccepted())
                .andReturn();
        var second = retry(ALPHA_AUTH, "task-alpha-retryable", "replay-contract-key", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .contains(extractAttemptId(first.getResponse().getContentAsString()));
        assertAtomicRowCounts(1);
    }

    @Test
    @Disabled("Contract: enable when idempotency-key payload binding is implemented")
    void sameKeyWithDifferentExpectedVersionReturns409() throws Exception {
        retry(ALPHA_AUTH, "task-alpha-retryable", "mismatch-contract-key", 0)
                .andExpect(status().isAccepted());
        retry(ALPHA_AUTH, "task-alpha-retryable", "mismatch-contract-key", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @Disabled("Contract: enable when optimistic version validation is implemented")
    void staleExpectedVersionReturns409WithoutWrites() throws Exception {
        retry(ALPHA_AUTH, "task-alpha-retryable", "stale-contract-key", 9)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_TASK_VERSION"));
        assertAtomicRowCounts(0);
    }

    @Test
    @Disabled("Contract: enable when retry lookup is tenant scoped")
    void crossTenantRetryIsConcealedAs404WithoutWrites() throws Exception {
        retry(BETA_AUTH, "task-alpha-retryable", "tenant-contract-key", 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        assertAtomicRowCounts(0);
    }

    @Test
    @Disabled("Contract: enable after adding a deterministic concurrent-request test")
    void concurrentSameKeyRequestsProduceOneAttemptAndOneReplay() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrentRetry(ready, start));
            var second = pool.submit(() -> concurrentRetry(ready, start));
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
    @Disabled("Contract: enable when all four writes share one transaction")
    void failureAfterOutboxInsertionRollsBackTheEntireChangeSet() throws Exception {
        doThrow(new IllegalStateException("injected failure"))
                .when(failureInjector)
                .afterOutboxInserted();

        retry(ALPHA_AUTH, "task-alpha-retryable", "rollback-contract-key", 0)
                .andExpect(status().is5xxServerError());

        assertThat(jdbc.queryForObject(
                        "select status from tasks where id = 'task-alpha-retryable'", String.class))
                .isEqualTo("FAILED_RETRYABLE");
        assertThat(jdbc.queryForObject(
                        "select version from tasks where id = 'task-alpha-retryable'", Long.class))
                .isZero();
        assertAtomicRowCounts(0);
    }

    private org.springframework.test.web.servlet.ResultActions retry(
            String authorization, String taskId, String key, long expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/workflows/workflow-alpha/tasks/" + taskId + "/retry")
                .header("Authorization", authorization)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + expectedVersion + "}"));
    }

    private org.springframework.test.web.servlet.MvcResult concurrentRetry(
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return retry(ALPHA_AUTH, "task-alpha-retryable", "concurrent-contract-key", 0).andReturn();
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
