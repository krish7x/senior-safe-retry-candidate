package com.complyance.assignment.retry.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RetryRequest(@NotNull @PositiveOrZero Long expectedVersion) {
}
