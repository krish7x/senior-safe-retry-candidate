package com.complyance.assignment.retry.domain;

import org.springframework.http.HttpStatus;

public final class RetryNotImplementedException extends AssignmentException {

    public RetryNotImplementedException() {
        super(
                "NOT_IMPLEMENTED",
                HttpStatus.NOT_IMPLEMENTED,
                "Safe retry behavior is intentionally left for the candidate to implement");
    }
}
