package com.complyance.assignment.retry.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, IdempotencyRecordId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from IdempotencyRecordEntity r where r.tenantId = :tenantId and r.idempotencyKey = :idempotencyKey")
    Optional<IdempotencyRecordEntity> lockByTenantIdAndIdempotencyKey(
            @Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey);
}
