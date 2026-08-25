package com.complyance.assignment.retry.domain;

public enum TaskStatus {
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    RETRY_QUEUED,
    RUNNING,
    SUCCEEDED
}
