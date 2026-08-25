package com.complyance.assignment.retry;

import static org.assertj.core.api.Assertions.assertThat;

import com.complyance.assignment.SafeRetryApplication;
import com.complyance.assignment.retry.application.RetryCommand;
import com.complyance.assignment.retry.application.RetryOutcome;
import com.complyance.assignment.retry.application.RetryService;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Disabled;
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
 * Real-PostgreSQL public contract. Enable after implementing safe retry.
 * Docker must be available; no sleep-based coordination is used.
 */
@Disabled("Enable after implementing the safe-retry transaction")
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

    @Test
    void postgresArbitratesConcurrentIdenticalRequests() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<RetryOutcome> action = () -> {
            ready.countDown();
            start.await();
            return retryService.retry(new RetryCommand(
                    "tenant-alpha",
                    "workflow-alpha",
                    "task-alpha-retryable",
                    "postgres-concurrent-key",
                    0));
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

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
