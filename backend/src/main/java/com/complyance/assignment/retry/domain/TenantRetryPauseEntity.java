package com.complyance.assignment.retry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The tenant-scoped retry pause gate. One row per tenant; its presence-and-flag records
 * whether new retries for that tenant are paused.
 *
 * <p>The row is also the serialization point for the pause-versus-retry race: a retry
 * takes a shared lock on it and a pause takes an exclusive lock, so PostgreSQL -- not the
 * application -- decides the ordering, and it does so across every application instance.
 */
@Entity
@Table(name = "tenant_retry_pause")
public class TenantRetryPauseEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "paused", nullable = false)
    private boolean paused;

    @Column(name = "paused_at")
    private Instant pausedAt;

    protected TenantRetryPauseEntity() {
    }

    public TenantRetryPauseEntity(String tenantId, boolean paused, Instant pausedAt) {
        this.tenantId = tenantId;
        this.paused = paused;
        this.pausedAt = pausedAt;
    }

    public String getTenantId() {
        return tenantId;
    }

    public boolean isPaused() {
        return paused;
    }

    public void pause(Instant now) {
        this.paused = true;
        this.pausedAt = now;
    }
}
