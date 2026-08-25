package com.complyance.assignment.retry.application;

import org.springframework.stereotype.Component;

@Component
final class NoOpRetryFailureInjector implements RetryFailureInjector {

    @Override
    public void afterOutboxInserted() {
        // Intentionally empty outside fault-injection tests.
    }
}
