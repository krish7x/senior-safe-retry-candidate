package com.complyance.assignment.retry.domain;

/**
 * Internal signal that PostgreSQL — not an application pre-check — rejected this
 * contender at commit time. It never reaches the API layer: {@code RetryService}
 * catches it and re-resolves the request in a fresh transaction.
 */
public final class IdempotencyRaceException extends RuntimeException {

    public IdempotencyRaceException(Throwable cause) {
        super("A concurrent transaction already claimed this idempotency key or task version", cause);
    }
}
