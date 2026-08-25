package com.complyance.assignment.retry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "tasks")
public class TaskEntity {

    @Id
    @Column(name = "id", nullable = false, length = 100)
    private String id;

    @Column(name = "workflow_id", nullable = false, length = 100)
    private String workflowId;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private TaskStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaskEntity() {
    }

    public String getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTitle() {
        return title;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public void queueRetry(Instant now) {
        status = TaskStatus.RETRY_QUEUED;
        updatedAt = now;
    }
}
