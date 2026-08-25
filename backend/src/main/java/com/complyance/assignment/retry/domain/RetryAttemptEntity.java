package com.complyance.assignment.retry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "retry_attempts")
public class RetryAttemptEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "workflow_id", nullable = false, length = 100)
    private String workflowId;

    @Column(name = "task_id", nullable = false, length = 100)
    private String taskId;

    @Column(name = "task_title", nullable = false, length = 200)
    private String taskTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "accepted_status", nullable = false, length = 40)
    private TaskStatus acceptedStatus;

    @Column(name = "accepted_version", nullable = false)
    private long acceptedVersion;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RetryAttemptEntity() {
    }

    public RetryAttemptEntity(
            String id,
            String tenantId,
            String workflowId,
            String taskId,
            String taskTitle,
            TaskStatus acceptedStatus,
            long acceptedVersion,
            String idempotencyKey,
            String requestFingerprint,
            Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.workflowId = workflowId;
        this.taskId = taskId;
        this.taskTitle = taskTitle;
        this.acceptedStatus = acceptedStatus;
        this.acceptedVersion = acceptedVersion;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskTitle() {
        return taskTitle;
    }

    public TaskStatus getAcceptedStatus() {
        return acceptedStatus;
    }

    public long getAcceptedVersion() {
        return acceptedVersion;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }
}
