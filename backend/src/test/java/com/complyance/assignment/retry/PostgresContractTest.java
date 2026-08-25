package com.complyance.assignment.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.complyance.assignment.SafeRetryApplication;
import com.complyance.assignment.retry.application.RetryCommand;
import com.complyance.assignment.retry.application.RetryInterleaveHook;
import com.complyance.assignment.retry.application.RetryOutcome;
import com.complyance.assignment.retry.application.RetryService;
import com.complyance.assignment.retry.domain.RetryConflictException;
import java.util.List;
import java.util.concurrent.Callable;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real-PostgreSQL public contract. Enable after implementing safe retry.
 * Docker must be available; no sleep-based coordination is used.
 */
@SpringBootTest(classes = SafeRetryApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@Sql(scripts = "/reset.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PostgresContractTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine3.22")
            .withDatabaseName("safe_retry_test")
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
    @MockitoBean private RetryInterleaveHook interleaveHook;

    @Test
    void postgresArbitratesConcurrentIdenticalRequests() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<RetryOutcome> action = () -> {
            ready.countDown();
            start.await();
            return retry("postgres-concurrent-key");
        };

        List<RetryOutcome> results;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(action);
            var second = pool.submit(action);
            ready.await();
            start.countDown();
            results = List.of(first.get(), second.get());
        }

        assertThat(results).extracting(RetryOutcome::replayed)
                .containsExactlyInAnyOrder(false, true);
        assertThat(results).extracting(RetryOutcome::attemptId)
                .containsOnly(results.getFirst().attemptId());
        assertThat(count("retry_attempts")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
        assertThat(count("outbox_messages")).isEqualTo(1);
    }

    @Test
    void postgresSameKeyContenderWaitsOnPrimaryKeyThenReplays() throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    reserved.countDown();
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release latch timed out");
                    }
                    return null;
                })
                .when(interleaveHook)
                .afterTaskLocked();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<RetryOutcome> owner = pool.submit(() -> retry("pg-same-key"));
            assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();
            Future<RetryOutcome> contender = pool.submit(() -> retry("pg-same-key"));
            assertBlocked(contender);
            release.countDown();
            RetryOutcome first = owner.get(15, TimeUnit.SECONDS);
            RetryOutcome second = contender.get(15, TimeUnit.SECONDS);
            assertThat(List.of(first.replayed(), second.replayed())).containsExactlyInAnyOrder(false, true);
            assertThat(first.attemptId()).isEqualTo(second.attemptId());
        }
        assertThat(count("retry_attempts")).isEqualTo(1);
    }

    @Test
    void postgresDifferentKeyContenderWaitsOnTaskLockThenConflicts() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    locked.countDown();
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release latch timed out");
                    }
                    return null;
                })
                .when(interleaveHook)
                .afterTaskLocked();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<RetryOutcome> owner = pool.submit(() -> retry("pg-diff-owner"));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<RetryOutcome> contender = pool.submit(() -> retry("pg-diff-contender"));
            assertBlocked(contender);
            release.countDown();
            assertThat(owner.get(15, TimeUnit.SECONDS).replayed()).isFalse();
            try {
                contender.get(15, TimeUnit.SECONDS);
                throw new AssertionError("expected conflict");
            } catch (java.util.concurrent.ExecutionException failure) {
                assertThat(failure.getCause()).isInstanceOf(RetryConflictException.class);
            }
        }
        assertThat(count("retry_attempts")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
        assertThat(count("outbox_messages")).isEqualTo(1);
    }

    private RetryOutcome retry(String key) {
        return retryService.retry(new RetryCommand(
                "tenant-alpha",
                "workflow-alpha",
                "task-alpha-retryable",
                key,
                0));
    }

    private static void assertBlocked(Future<RetryOutcome> contender) throws Exception {
        try {
            contender.get(500, TimeUnit.MILLISECONDS);
            throw new AssertionError("contender completed before the owner released the PostgreSQL lock");
        } catch (TimeoutException expected) {
            // Blocked at the unique index or FOR UPDATE boundary.
        }
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
