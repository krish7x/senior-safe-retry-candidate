package com.complyance.assignment.retry.application;

import com.complyance.assignment.retry.domain.InvalidRetryRequestException;
import java.util.regex.Pattern;

/** Published Idempotency-Key grammar: 8-120 ASCII characters. */
final class IdempotencyKeys {

    private static final Pattern GRAMMAR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{7,119}");

    private IdempotencyKeys() {
    }

    static String require(String candidate) {
        if (candidate == null || !GRAMMAR.matcher(candidate).matches()) {
            throw new InvalidRetryRequestException(
                    "Idempotency-Key must be 8 to 120 characters using letters, digits, '.', '_', ':' or '-'");
        }
        return candidate;
    }

    /**
     * Correlation value for logs. The raw key is caller-supplied and tenant-scoped,
     * so it is never written to a log line.
     */
    static String correlationHash(String key) {
        return RetryFingerprint.sha256Hex(key).substring(0, 12);
    }
}
