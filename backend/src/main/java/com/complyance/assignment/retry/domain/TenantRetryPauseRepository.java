package com.complyance.assignment.retry.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRetryPauseRepository extends JpaRepository<TenantRetryPauseEntity, String> {

    /**
     * Shared lock on the tenant's pause gate ({@code select ... for share}). Many retries
     * can hold it at once, so retries do not serialise against each other, but it conflicts
     * with the exclusive lock a pause takes. That is the read side of the reader/writer gate:
     * a retry that has read "not paused" holds this lock until it commits, so a concurrent
     * pause must wait for that retry to finish before it can return.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select gate from TenantRetryPauseEntity gate where gate.tenantId = :tenantId")
    Optional<TenantRetryPauseEntity> lockSharedForRetry(@Param("tenantId") String tenantId);

    /**
     * Exclusive lock on the tenant's pause gate ({@code select ... for update}). It waits
     * for every in-flight retry holding the shared lock, and blocks new ones, so once the
     * pause has committed no retry that read "not paused" can still be running.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select gate from TenantRetryPauseEntity gate where gate.tenantId = :tenantId")
    Optional<TenantRetryPauseEntity> lockExclusiveForPause(@Param("tenantId") String tenantId);
}
