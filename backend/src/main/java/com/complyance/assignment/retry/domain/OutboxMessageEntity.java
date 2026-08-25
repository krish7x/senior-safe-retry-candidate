package com.complyance.assignment.retry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "outbox_messages")
public class OutboxMessageEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "attempt_id", nullable = false, length = 36)
    private String attemptId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "payload", nullable = false, length = 1000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxMessageEntity() {
    }

    public OutboxMessageEntity(
            String id,
            String tenantId,
            String aggregateId,
            String attemptId,
            String eventType,
            String payload,
            Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.aggregateId = aggregateId;
        this.attemptId = attemptId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }
}
