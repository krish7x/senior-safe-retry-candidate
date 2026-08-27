package com.complyance.assignment.retry.application;

import com.complyance.assignment.retry.domain.TenantRetryPauseEntity;
import com.complyance.assignment.retry.domain.TenantRetryPauseRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pauses new retries for one authenticated tenant.
 *
 * <p>The pause is durable (a database row, so it survives a restart) and idempotent
 * (pausing an already-paused tenant is a no-op that still succeeds). It takes an exclusive
 * lock on the tenant's gate row, which is the write side of the reader/writer gate the
 * retry transaction reads under a shared lock: once this transaction commits, no retry that
 * observed "not paused" can still be in flight, so no such retry can commit afterwards.
 */
@Service
public class RetryPauseService {

    private static final Logger log = LoggerFactory.getLogger(RetryPauseService.class);

    private final TenantRetryPauseRepository pauses;

    RetryPauseService(TenantRetryPauseRepository pauses) {
        this.pauses = pauses;
    }

    @Transactional
    public void pause(String tenantId) {
        var gate = pauses.lockExclusiveForPause(tenantId).orElse(null);
        if (gate == null) {
            // No provisioned gate row (e.g. a tenant added without a seed row). The primary
            // key makes a concurrent first-pause insert safe: one wins, the other would fail
            // its insert rather than double-pause.
            pauses.save(new TenantRetryPauseEntity(tenantId, true, Instant.now()));
        } else if (!gate.isPaused()) {
            gate.pause(Instant.now());
        }
        log.atInfo().addKeyValue("tenantId", tenantId).log("Tenant retries paused");
    }
}
