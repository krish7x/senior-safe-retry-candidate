package com.complyance.assignment.retry.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetryAttemptRepository extends JpaRepository<RetryAttemptEntity, String> {

    Optional<RetryAttemptEntity> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);
}
