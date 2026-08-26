package com.complyance.assignment.retry.application;

import com.complyance.assignment.retry.domain.IdempotencyRaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Safe-retry entry point.
 *
 * <p>Deliberately not transactional. It validates the request, then delegates to
 * {@link RetryTransaction}, whose transaction owns every database effect. When
 * PostgreSQL rejects a contender at commit time, the losing transaction is already
 * dead, so the outcome has to be re-resolved in a new one — which is only possible
 * from outside the failed transaction.
 */
@Service
public class RetryService {

    private static final Logger log = LoggerFactory.getLogger(RetryService.class);

    private final RetryTransaction transaction;

    RetryService(RetryTransaction transaction) {
        this.transaction = transaction;
    }

    public RetryOutcome retry(RetryCommand command) {
        IdempotencyKeys.require(command.idempotencyKey());
        var fingerprint = RetryFingerprint.of(command);

        RetryOutcome outcome;
        try {
            outcome = transaction.accept(command, fingerprint);
        } catch (IdempotencyRaceException race) {
            // The database decided this contender lost. Read the committed winner and
            // answer from it instead of guessing in Java.
            log.atInfo()
                    .addKeyValue("tenantId", command.tenantId())
                    .addKeyValue("workflowId", command.workflowId())
                    .addKeyValue("taskId", command.taskId())
                    .addKeyValue("idempotencyKeyHash", IdempotencyKeys.correlationHash(command.idempotencyKey()))
                    .log("Retry lost a database-arbitrated race; re-resolving against committed state");
            outcome = transaction.resolveLostRace(command, fingerprint);
        }

        // Safe fields only. The bearer token, the raw Idempotency-Key, and the
        // fingerprint preimage are never logged, and no other tenant is referenced.
        log.atInfo()
                .addKeyValue("tenantId", command.tenantId())
                .addKeyValue("workflowId", outcome.workflowId())
                .addKeyValue("taskId", outcome.id())
                .addKeyValue("attemptId", outcome.attemptId())
                .addKeyValue("acceptedVersion", outcome.version())
                .addKeyValue("replayed", outcome.replayed())
                .addKeyValue("idempotencyKeyHash", IdempotencyKeys.correlationHash(command.idempotencyKey()))
                .log("Retry resolved");

        return outcome;
    }
}
