package com.complyance.assignment.retry.application;

import com.complyance.assignment.retry.domain.TaskStatus;

public record RetryOutcome(
        String id,
        String workflowId,
        String title,
        TaskStatus status,
        long version,
        String attemptId,
        boolean replayed) {
}
