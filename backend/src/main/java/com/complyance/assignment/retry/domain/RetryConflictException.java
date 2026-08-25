package com.complyance.assignment.retry.domain;

import org.springframework.http.HttpStatus;

/** Published conflict vocabulary; candidates decide where and how to enforce it safely. */
public class RetryConflictException extends AssignmentException {

    public RetryConflictException(String code, String message) {
        super(code, HttpStatus.CONFLICT, message);
    }

    public static RetryConflictException staleVersion(long expected, long actual) {
        return new RetryConflictException(
                "STALE_TASK_VERSION",
                "Expected task version " + expected + " but current version is " + actual);
    }

    public static RetryConflictException notRetryable(TaskStatus status) {
        return new RetryConflictException(
                "TASK_NOT_RETRYABLE", "Task in status " + status + " cannot be retried");
    }

    public static RetryConflictException reusedKey() {
        return new RetryConflictException(
                "IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used for a different request");
    }
}
