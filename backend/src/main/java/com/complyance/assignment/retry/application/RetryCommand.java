package com.complyance.assignment.retry.application;

public record RetryCommand(
        String tenantId,
        String workflowId,
        String taskId,
        String idempotencyKey,
        long expectedVersion) {
}
