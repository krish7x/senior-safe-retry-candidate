package com.complyance.assignment.retry.domain;

import org.springframework.http.HttpStatus;

public final class InvalidRetryRequestException extends AssignmentException {

    public InvalidRetryRequestException(String message) {
        super("INVALID_REQUEST", HttpStatus.BAD_REQUEST, message);
    }
}
