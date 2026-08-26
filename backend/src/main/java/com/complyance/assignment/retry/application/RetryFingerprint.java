package com.complyance.assignment.retry.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical request fingerprint: authenticated tenant, route workflow, route task,
 * and numeric expected version.
 *
 * <p>Fields are length-prefixed before hashing so no combination of field values can
 * produce the same preimage as a different combination.
 */
final class RetryFingerprint {

    private RetryFingerprint() {
    }

    static String of(RetryCommand command) {
        var canonical = new StringBuilder("retry-fingerprint-v1");
        appendField(canonical, command.tenantId());
        appendField(canonical, command.workflowId());
        appendField(canonical, command.taskId());
        appendField(canonical, Long.toString(command.expectedVersion()));
        return sha256Hex(canonical.toString());
    }

    static String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required on every supported JVM", impossible);
        }
    }

    private static void appendField(StringBuilder target, String field) {
        target.append('|').append(field.length()).append(':').append(field);
    }
}
