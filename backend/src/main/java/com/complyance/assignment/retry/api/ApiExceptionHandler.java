package com.complyance.assignment.retry.api;

import com.complyance.assignment.retry.domain.AssignmentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AssignmentException.class)
    ResponseEntity<ApiError> assignmentError(AssignmentException failure) {
        return ResponseEntity.status(failure.status())
                .body(new ApiError(failure.status().value(), failure.code(), failure.getMessage()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class,
        HttpMessageNotReadableException.class,
        BindException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception failure) {
        return ResponseEntity.badRequest()
                .body(new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        "INVALID_REQUEST",
                        "Request validation failed"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpectedFailure(Exception failure) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "INTERNAL_ERROR",
                        "The request could not be completed"));
    }
}
