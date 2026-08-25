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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskEntity t where t.id = :id and t.tenantId = :tenantId")
    Optional<TaskEntity> lockByIdAndTenantId(@Param("id") String id, @Param("tenantId") String tenantId);
}
