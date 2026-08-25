package com.complyance.assignment.retry.application;

import org.springframework.stereotype.Component;

@Component
final class NoOpRetryInterleaveHook implements RetryInterleaveHook {

    @Override
    public void afterIdempotencyOwnerReserved() {
        // Intentionally empty outside interleave evidence tests.
    }

    @Override
    public void afterTaskLocked() {
        // Intentionally empty outside interleave evidence tests.
    }
}
