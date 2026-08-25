package com.complyance.assignment.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.complyance.assignment.SafeRetryApplication;
import com.complyance.assignment.retry.application.RetryCommand;
import com.complyance.assignment.retry.application.RetryInterleaveHook;
import com.complyance.assignment.retry.application.RetryOutcome;
import com.complyance.assignment.retry.application.RetryService;
import com.complyance.assignment.retry.domain.RetryConflictException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

/**
 * Controlled-interleaving evidence. A start barrier is not used as the sole proof:
 * the first transaction holds a write lock at a known boundary while the contender
 * is shown to remain blocked, then released.
 */
@SpringBootTest(classes = SafeRetryApplication.class)
@ActiveProfiles("test")
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class RetryConcurrencyEvidenceTest {

    @Autowired private RetryService retryService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private RetryInterleaveHook interleaveHook;

    @Test
    void sameKeyContenderWaitsOnIdempotencyPrimaryKeyThenReplays() throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    reserved.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release latch timed out");
                    }
                    return null;
                })
                .when(interleaveHook)
                .afterTaskLocked();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<RetryOutcome> owner = pool.submit(() -> retry("same-key-evidence"));
            assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();

            Future<RetryOutcome> contender = pool.submit(() -> retry("same-key-evidence"));
            assertContenderStillBlocked(contender);

            release.countDown();
            RetryOutcome first = owner.get(10, TimeUnit.SECONDS);
            RetryOutcome second = contender.get(10, TimeUnit.SECONDS);

            assertThat(first.replayed()).isFalse();
            assertThat(second.replayed()).isTrue();
            assertThat(second.attemptId()).isEqualTo(first.attemptId());
        }
        assertCounts(1);
    }

    @Test
    void differentKeyContenderWaitsOnTaskRowLockThenConflicts() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    locked.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release latch timed out");
                    }
                    return null;
                })
                .when(interleaveHook)
                .afterTaskLocked();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<RetryOutcome> owner = pool.submit(() -> retry("different-key-owner"));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<RetryOutcome> contender = pool.submit(() -> retry("different-key-contender"));
            assertContenderStillBlocked(contender);

            release.countDown();
            RetryOutcome accepted = owner.get(10, TimeUnit.SECONDS);
            assertThat(accepted.replayed()).isFalse();

            try {
                contender.get(10, TimeUnit.SECONDS);
                throw new AssertionError("expected the contender to lose the version/state race");
            } catch (java.util.concurrent.ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOf(RetryConflictException.class);
                assertThat(((RetryConflictException) failure.getCause()).code())
                        .isIn("STALE_TASK_VERSION", "TASK_NOT_RETRYABLE");
            }
        }
        assertCounts(1);
    }

    private RetryOutcome retry(String key) {
        return retryService.retry(new RetryCommand(
                "tenant-alpha", "workflow-alpha", "task-alpha-retryable", key, 0));
    }

    private static void assertContenderStillBlocked(Future<RetryOutcome> contender) throws Exception {
        try {
            contender.get(400, TimeUnit.MILLISECONDS);
            throw new AssertionError("contender completed before the owner released the database lock");
        } catch (TimeoutException expected) {
            // The contender is waiting at the PostgreSQL/H2 write boundary, not at a start barrier.
        }
    }

    private void assertCounts(long expected) {
        assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from outbox_messages", Long.class)).isEqualTo(expected);
    }
}
