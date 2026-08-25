package com.complyance.assignment.retry.domain;

import org.springframework.http.HttpStatus;

public abstract class AssignmentException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    protected AssignmentException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
