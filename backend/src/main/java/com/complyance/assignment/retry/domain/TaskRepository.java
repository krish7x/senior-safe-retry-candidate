package com.complyance.assignment.retry.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    List<TaskEntity> findByTenantIdOrderByIdAsc(String tenantId);

    Optional<TaskEntity> findByIdAndTenantId(String id, String tenantId);

    /**
     * Concurrency-safe write lookup: {@code select ... for update} on the single task
     * row, scoped to the authenticated tenant and the route workflow.
     *
     * <p>The row lock is what serialises every contender for one task. A second
     * transaction blocks here until the first commits or rolls back, and PostgreSQL
     * then re-evaluates the predicate against the newly committed row, so the waiter
     * observes the winner's status and version rather than its own stale snapshot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select task from TaskEntity task
            where task.id = :taskId
              and task.tenantId = :tenantId
              and task.workflowId = :workflowId
            """)
    Optional<TaskEntity> lockForRetry(
            @Param("tenantId") String tenantId,
            @Param("workflowId") String workflowId,
            @Param("taskId") String taskId);
}
