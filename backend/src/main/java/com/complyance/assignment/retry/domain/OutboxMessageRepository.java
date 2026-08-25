package com.complyance.assignment.retry.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessageEntity, String> {
}
