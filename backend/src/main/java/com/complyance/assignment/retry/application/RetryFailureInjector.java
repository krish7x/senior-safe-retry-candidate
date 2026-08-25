package com.complyance.assignment.retry.application;

/** Deterministic test seam called after the outbox insert and before transaction completion. */
public interface RetryFailureInjector {

    void afterOutboxInserted();
}
