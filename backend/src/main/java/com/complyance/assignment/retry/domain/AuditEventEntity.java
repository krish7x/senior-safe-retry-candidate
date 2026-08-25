package com.complyance.assignment.retry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "task_id", nullable = false, length = 100)
    private String taskId;

    @Column(name = "attempt_id", nullable = false, length = 36)
    private String attemptId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEventEntity() {
    }

    public AuditEventEntity(
            String id, String tenantId, String taskId, String attemptId, String eventType, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.taskId = taskId;
        this.attemptId = attemptId;
        this.eventType = eventType;
        this.createdAt = createdAt;
    }
}
