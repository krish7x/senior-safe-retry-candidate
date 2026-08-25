package com.complyance.assignment.retry.application;

/**
 * Test-only seam for controlled interleaving at lock/write boundaries.
 * Production is a no-op. Do not use real sleeps for concurrency evidence.
 */
public interface RetryInterleaveHook {

    void afterIdempotencyOwnerReserved();

    void afterTaskLocked();
}
