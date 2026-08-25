package com.complyance.assignment.retry.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    List<TaskEntity> findByTenantIdOrderByIdAsc(String tenantId);

    Optional<TaskEntity> findByIdAndTenantId(String id, String tenantId);

    // TODO: Add the concurrency-safe write lookup or conditional update your design requires.
}
