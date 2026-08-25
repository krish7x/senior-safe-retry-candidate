package com.complyance.assignment.retry.domain;

import java.io.Serializable;
import java.util.Objects;

public class IdempotencyRecordId implements Serializable {

    private String tenantId;
    private String idempotencyKey;

    public IdempotencyRecordId() {
    }

    public IdempotencyRecordId(String tenantId, String idempotencyKey) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IdempotencyRecordId that)) {
            return false;
        }
        return Objects.equals(tenantId, that.tenantId)
                && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, idempotencyKey);
    }
}
