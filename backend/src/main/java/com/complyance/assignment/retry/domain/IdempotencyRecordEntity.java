package com.complyance.assignment.retry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "idempotency_records")
@IdClass(IdempotencyRecordId.class)
public class IdempotencyRecordEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "attempt_id", length = 36)
    private String attemptId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(
            String tenantId,
            String idempotencyKey,
            String requestFingerprint,
            String attemptId,
            Instant createdAt) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.attemptId = attemptId;
        this.createdAt = createdAt;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void attachAttempt(String attemptId) {
        this.attemptId = attemptId;
    }
}
