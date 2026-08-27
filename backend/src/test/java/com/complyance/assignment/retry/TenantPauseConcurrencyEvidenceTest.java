package com.complyance.assignment.retry;

import static org.assertj.core.api.Assertions.assertThat;

import com.complyance.assignment.SafeRetryApplication;
import com.complyance.assignment.retry.application.RetryCommand;
import com.complyance.assignment.retry.application.RetryOutcome;
import com.complyance.assignment.retry.application.RetryPauseService;
import com.complyance.assignment.retry.application.RetryService;
import com.complyance.assignment.retry.domain.TenantRetriesPausedException;
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
 * Deterministic controlled-interleaving evidence for the pause-versus-retry race on real
 * PostgreSQL. As in {@link RetryConcurrencyEvidenceTest}, a start barrier is not used as
 * proof; instead the test <strong>holds a known lock on the tenant pause gate row</strong>
 * from a connection it controls and uses {@code pg_blocking_pids()} to observe, as committed
 * database state, that the contender is parked on that lock before it is released.
 *
 * <p>The gate is a reader/writer lock: a retry reads it {@code for share}, a pause takes it
 * {@code for update}. The two tests prove both directions of the mandated ordering:
 *
 * <ol>
 *   <li><b>Pause wins.</b> A retry that arrives while a pause holds the exclusive gate lock
 *       parks on it, and once the pause commits the retry loses with
 *       {@code TENANT_RETRIES_PAUSED} and writes nothing.</li>
 *   <li><b>Retry commits first.</b> A pause that arrives while a retry holds the shared gate
 *       lock parks on it, so the pause cannot return until that in-flight retry has finished.
 *       This is exactly why "once the pause returned, no retry may commit" holds.</li>
 * </ol>
 */
@SpringBootTest(classes = SafeRetryApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TenantPauseConcurrencyEvidenceTest {

    private static final String TENANT = "tenant-alpha";
    private static final String TASK_ID = "task-alpha-retryable";
    private static final Duration BARRIER_TIMEOUT = Duration.ofSeconds(20);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine3.22")
            .withDatabaseName("safe_retry_pause_evidence")
            .withUsername("safe_retry")
            .withPassword("safe_retry");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private RetryService retryService;
    @Autowired private RetryPauseService retryPauseService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    void aRetryArrivingDuringAPauseParksOnTheGateThenLosesAndWritesNothing() throws Exception {
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            // Simulate a pause in progress: hold the exclusive lock on the gate row.
            long blockerPid = lockGate(blocker, "for update");

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Attempt> retry = pool.submit(retryAttempt("pause-wins-key"));
                // The retry has locked the task row and is now parked on the gate's shared lock.
                awaitBackendsBlockedBy(blockerPid, 1);
                // Nothing is written while it waits.
                assertRowCounts(0);
                assertThat(taskVersion()).isZero();

                // Commit the pause and release the exclusive lock.
                try (var statement = blocker.createStatement()) {
                    statement.executeUpdate(
                            "update tenant_retry_pause set paused = true, paused_at = now()"
                                    + " where tenant_id = '" + TENANT + "'");
                }
                blocker.commit();

                var result = retry.get();
                assertThat(result.outcome()).isNull();
                assertThat(result.failure()).isInstanceOf(TenantRetriesPausedException.class);

                // The rejected retry changed nothing: no task change, attempt, audit, or outbox.
                assertRowCounts(0);
                assertThat(taskVersion()).isZero();
                assertThat(jdbc.queryForObject(
                                "select status from tasks where id = ?", String.class, TASK_ID))
                        .isEqualTo("FAILED_RETRYABLE");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void aPauseArrivingDuringAnInFlightRetryParksUntilThatRetryFinishes() throws Exception {
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            // Simulate an in-flight retry that has read "not paused": hold the shared gate lock.
            long blockerPid = lockGate(blocker, "for share");

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<RuntimeException> pause = pool.submit(pauseAttempt());
                // The pause cannot take the exclusive lock while the shared lock is held.
                awaitBackendsBlockedBy(blockerPid, 1);
                assertThat(gatePaused()).isFalse();

                // The in-flight retry finishes and releases the shared lock.
                blocker.rollback();

                assertThat(pause.get()).as("the pause completes once the retry releases the gate").isNull();
                assertThat(gatePaused()).isTrue();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private long lockGate(Connection blocker, String lockClause) throws SQLException {
        try (var statement = blocker.createStatement()) {
            try (var pid = statement.executeQuery("select pg_backend_pid()")) {
                pid.next();
                var backendPid = pid.getLong(1);
                statement.execute(
                        "select tenant_id from tenant_retry_pause where tenant_id = '" + TENANT + "' " + lockClause);
                return backendPid;
            }
        }
    }

    private Callable<Attempt> retryAttempt(String idempotencyKey) {
        return () -> {
            try {
                return new Attempt(retryService.retry(new RetryCommand(
                        TENANT, "workflow-alpha", TASK_ID, idempotencyKey, 0)), null);
            } catch (RuntimeException failure) {
                return new Attempt(null, failure);
            }
        };
    }

    private Callable<RuntimeException> pauseAttempt() {
        return () -> {
            try {
                retryPauseService.pause(TENANT);
                return null;
            } catch (RuntimeException failure) {
                return failure;
            }
        };
    }

    private void awaitBackendsBlockedBy(long blockerPid, int expected) {
        var deadline = Instant.now().plus(BARRIER_TIMEOUT);
        long observed;
        do {
            observed = backendsBlockedBy(blockerPid);
            if (observed == expected) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        } while (Instant.now().isBefore(deadline));

        throw new AssertionError(
                "Expected " + expected + " backend(s) blocked by pid " + blockerPid + " within "
                        + BARRIER_TIMEOUT + " but observed " + observed
                        + ". The pause and retry are not contending on the gate row.");
    }

    private long backendsBlockedBy(long blockerPid) {
        return jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database()
                  and ? = any(pg_blocking_pids(pid))
                """, Long.class, blockerPid);
    }

    private boolean gatePaused() {
        return jdbc.queryForObject(
                "select paused from tenant_retry_pause where tenant_id = ?", Boolean.class, TENANT);
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
}
