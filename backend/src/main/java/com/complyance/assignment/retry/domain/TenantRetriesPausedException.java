package com.complyance.assignment.retry.domain;

import org.springframework.http.HttpStatus;

/**
 * Raised when a new retry is attempted for a tenant whose retries have been paused. The
 * pause is checked inside the retry transaction, after the task row is locked and before
 * any effect is written, so a rejected retry changes nothing.
 */
public final class TenantRetriesPausedException extends AssignmentException {

    public TenantRetriesPausedException() {
        super("TENANT_RETRIES_PAUSED", HttpStatus.CONFLICT, "Retries are paused for this tenant");
    }
}
