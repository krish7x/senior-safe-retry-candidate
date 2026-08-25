package com.complyance.assignment.retry.api;

public record ApiError(int status, String code, String message) {
}
