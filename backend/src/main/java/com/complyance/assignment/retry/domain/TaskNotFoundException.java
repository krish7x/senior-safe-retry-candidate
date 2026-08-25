package com.complyance.assignment.retry.domain;

import org.springframework.http.HttpStatus;

public final class TaskNotFoundException extends AssignmentException {

    public TaskNotFoundException() {
        super("TASK_NOT_FOUND", HttpStatus.NOT_FOUND, "Task was not found");
    }
}
