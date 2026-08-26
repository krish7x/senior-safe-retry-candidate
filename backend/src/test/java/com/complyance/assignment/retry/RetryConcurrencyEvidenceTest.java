package com.complyance.assignment.retry;

import static org.assertj.core.api.Assertions.assertThat;

import com.complyance.assignment.SafeRetryApplication;
import com.complyance.assignment.retry.application.RetryCommand;
import com.complyance.assignment.retry.application.RetryOutcome;
import com.complyance.assignment.retry.application.RetryService;
import com.complyance.assignment.retry.domain.RetryConflictException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Deterministic controlled-interleaving evidence on real PostgreSQL.
 *
 * <p>A start barrier only releases two calls at roughly the same moment; it never proves
 * that the two transactions were actually overlapping at the contested row. These tests
 * instead <strong>hold a known database lock</strong> on the target task row from a
 * connection the test controls, and then use {@code pg_blocking_pids()} to observe, as
 * committed database state, that each contender is parked on that lock before the lock is
 * released. That establishes the ordering the assertions depend on:
 *
 * <ol>
 *   <li>the contender has already run its idempotency lookup and found nothing, and</li>
 *   <li>it is waiting at the task row when the winning transaction commits.</li>
 * </ol>
 *
 * <p>No test sleeps for a fixed duration. Every wait is a poll on an observable database
 * condition with a deadline, so a broken implementation fails instead of passing slowly.
 * PostgreSQL grants a contended row lock to waiters in queue order, so the contender that
 * is observed blocking first is the contender that wins.
 */
@SpringBootTest(classes = SafeRetryApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class RetryConcurrencyEvidenceTest {

    private static final String TASK_ID = "task-alpha-retryable";
    private static final Duration BARRIER_TIMEOUT = Duration.ofSeconds(20);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine3.22")
            .withDatabaseName("safe_retry_evidence")
            .withUsername("safe_retry")
            .withPassword("safe_retry");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private RetryService retryService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    /**
     * Same key, provably overlapping transactions: exactly one acceptance, one replay of
     * that same attempt, and one set of records.
     */
    @Test
    void sameKeyContenderParksOnTheTaskRowAndReplaysTheWinnersAttempt() throws Exception {
        var results = runContendedRetries("same-key-interleaving-evidence", "same-key-interleaving-evidence");

        var winner = results.accepted();
        var waiter = results.contender().outcome();

        assertThat(winner.replayed()).isFalse();
        assertThat(winner.version()).isEqualTo(1);
        assertThat(waiter).isNotNull();
        assertThat(waiter.replayed()).isTrue();
        // The waiter replays the winner's result rather than producing its own.
        assertThat(waiter.attemptId()).isEqualTo(winner.attemptId());
        assertThat(waiter.version()).isEqualTo(winner.version());
        assertThat(waiter.status()).isEqualTo(winner.status());

        assertRowCounts(1);
        assertThat(taskVersion()).isEqualTo(1);
    }

    /**
     * Different keys against the same task and version: one acceptance and one conflict.
     * The loser is the transaction that was observed waiting, and it loses on the version
     * the winner had already consumed — not on an application-level pre-check.
     */
    @Test
    void differentKeyContenderParksOnTheTaskRowAndThenLosesOnVersion() throws Exception {
        var results = runContendedRetries("different-key-winner-01", "different-key-contender-01");

        assertThat(results.accepted().replayed()).isFalse();
        assertThat(results.accepted().version()).isEqualTo(1);

        assertThat(results.contender().outcome()).isNull();
        assertThat(results.contender().failure())
                .isInstanceOf(RetryConflictException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(RetryConflictException.class))
                .extracting(RetryConflictException::code)
                .isEqualTo("STALE_TASK_VERSION");

        assertRowCounts(1);
        assertThat(taskVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select idempotency_key from retry_attempts", String.class))
                .isEqualTo("different-key-winner-01");
    }

    /**
     * Runs two retries whose transactions are proven to overlap at the task row.
     *
     * <p>Both contenders are parked on a lock this test holds, so both have already
     * completed their idempotency lookup against a table that still holds no rows.
     */
    private ContendedRun runContendedRetries(String winnerKey, String contenderKey) throws Exception {
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            var blockerPid = lockTaskRow(blocker);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Attempt> winner = pool.submit(attempt(winnerKey));
                // Queue order decides the winner, so the first contender must be observed
                // waiting before the second one is even submitted.
                awaitBlockedBackends(1);
                assertThat(backendsBlockedBy(blockerPid))
                        .as("the first retry must be waiting on the lock this test holds")
                        .isEqualTo(1);

                Future<Attempt> contender = pool.submit(attempt(contenderKey));
                awaitBlockedBackends(2);

                // Nothing has been written while both transactions are parked: whatever the
                // contender read during its idempotency lookup, it read an empty table.
                assertRowCounts(0);
                assertThat(taskVersion()).isZero();

                // Releasing the lock hands the row to the first waiter; the second waiter
                // continues to wait for that transaction to commit.
                blocker.rollback();

                var winnerAttempt = winner.get();
                var contenderAttempt = contender.get();
                assertThat(winnerAttempt.failure())
                        .as("the first contender in the lock queue must be the accepted one")
                        .isNull();
                return new ContendedRun(winnerAttempt.outcome(), contenderAttempt);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /** Takes and holds a row lock on the target task, returning the holder's backend pid. */
    private long lockTaskRow(Connection blocker) throws SQLException {
        try (var statement = blocker.createStatement()) {
            try (var pid = statement.executeQuery("select pg_backend_pid()")) {
                pid.next();
                var backendPid = pid.getLong(1);
                statement.execute("select id from tasks where id = '" + TASK_ID + "' for update");
                return backendPid;
            }
        }
    }

    private Callable<Attempt> attempt(String idempotencyKey) {
        return () -> {
            try {
                return new Attempt(retryService.retry(new RetryCommand(
                        "tenant-alpha", "workflow-alpha", TASK_ID, idempotencyKey, 0)), null);
            } catch (RuntimeException failure) {
                return new Attempt(null, failure);
            }
        };
    }

    /**
     * Waits until PostgreSQL reports exactly {@code expected} blocked backends. This is a
     * condition wait with a deadline, not a timed sleep: the loop only ends when the
     * database itself reports the interleaving, or the test fails.
     */
    private void awaitBlockedBackends(int expected) {
        var deadline = Instant.now().plus(BARRIER_TIMEOUT);
        long observed;
        do {
            observed = blockedBackends();
            if (observed == expected) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        } while (Instant.now().isBefore(deadline));

        throw new AssertionError(
                "Expected " + expected + " backend(s) blocked on the task row within " + BARRIER_TIMEOUT
                        + " but observed " + observed
                        + ". The retry transaction is not contending at the database boundary.");
    }

    private long blockedBackends() {
        return jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database()
                  and cardinality(pg_blocking_pids(pid)) > 0
                """, Long.class);
    }

    private long backendsBlockedBy(long blockerPid) {
        return jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database()
                  and ? = any(pg_blocking_pids(pid))
                """, Long.class, blockerPid);
    }

    private long taskVersion() {
        return jdbc.queryForObject("select version from tasks where id = ?", Long.class, TASK_ID);
    }

    private void assertRowCounts(long expected) {
        assertThat(jdbc.queryForObject("select count(*) from retry_attempts", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select count(*) from outbox_messages", Long.class)).isEqualTo(expected);
    }

    private record Attempt(RetryOutcome outcome, RuntimeException failure) {
    }

    private record ContendedRun(RetryOutcome accepted, Attempt contender) {
    }
}
