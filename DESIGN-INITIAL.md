# Initial Design — Safe Retry

Complete this file before coding and checkpoint it at 0:25. Keep it unchanged after the checkpoint. Use concise bullets; the whole document must stay under **800 words**, excluding the diagram and table.

## 1. Scope and invariants

- Base scope: one logical retry from the React UI through `POST /api/workflows/{workflowId}/tasks/{taskId}/retry` into PostgreSQL. Done when one retry attempt, one `TASK_RETRY_QUEUED` audit event, and one `TASK_RETRY_REQUESTED` outbox row commit together.
- Out of scope: outbox consumers, extra APIs, visual redesign, schedulers, real identity, the reserved V100 change request.
- Invariants:
  - Tenant is taken only from the bearer principal.
  - Missing, cross-tenant, and workflow-mismatched tasks all return `TASK_NOT_FOUND` (no enumeration).
  - At most one accept for a given task version while status is `FAILED_RETRYABLE`.
  - Idempotency key is unique per tenant. Same key + same fingerprint replays. Same key + any different fingerprint is `IDEMPOTENCY_KEY_REUSED`, even if the new task is missing.
  - Accept writes live in one database transaction; `afterOutboxInserted()` failure rolls them all back.
  - Stale `expectedVersion` or a non-retryable status is `409` with no writes.

## 2. Request-to-database design

```text
Browser (one Idempotency-Key per click; never send tenant)
    |  trust boundary: Authorization bearer only
    v
RetryController -> RetryService
    |  transaction starts
    |  optional pg_advisory_xact_lock(tenant, key) on PostgreSQL
    |  reserve/lock idempotency row (tenant, key)
    |     existing + same fingerprint -> replay
    |     existing + different fingerprint -> 409
    |  SELECT tasks FOR UPDATE (tenant, taskId)
    |  require FAILED_RETRYABLE and expectedVersion
    |  UPDATE RETRY_QUEUED / version+1
    |  INSERT attempt, audit, outbox; bind attempt id to key
    |  RetryFailureInjector.afterOutboxInserted()
    `  COMMIT
       PostgreSQL unique (tenant,key) and row locks decide races
```

## 3. API, data, and transaction choices

- Validation: key must match `[A-Za-z0-9][A-Za-z0-9._:-]{7,119}`. Missing header / unreadable JSON -> `INVALID_REQUEST`. Messages never include SQL, stacks, or tokens.
- Fingerprint: SHA-256 of tenant + workflowId + taskId + expectedVersion. Replay returns the original attempt fields, `replayed=true`, HTTP 200.
- Concurrency: pessimistic lock on the task row; Java version/status checks are not sufficient alone. Unique index on `retry_attempts (tenant_id, idempotency_key)`. Same-key second caller waits on the unique/lock, then replays. Different-key second caller waits on the task row, then `409`.
- Schema: additive V3 only. New `idempotency_records` primary key `(tenant_id, idempotency_key)`. Replace the non-unique lookup index with a unique index. Do not change V1/V2 core columns. Leave V100 unused. New canonical `NOT NULL` columns would need defaults; none planned.
- Rollback: one `TransactionTemplate` / Spring transaction around all writes; injector `RuntimeException` aborts the unit of work. Rolled-back keys leave no stored result.

## 4. Failure mitigation

| Failure | Invariant at risk | Prevention | Detection | Recovery | Planned evidence |
|---|---|---|---|---|---|
| Simultaneous retries, same or different keys | Single accept | Task `FOR UPDATE` + unique `(tenant,key)` | 202 + 200 or 409 | Replay or client new key | Test hook while a lock is held; Postgres contract |
| Same tenant key, different fingerprint | Key binding | Compare fingerprint before task work | `IDEMPOTENCY_KEY_REUSED` | New key | Contract including a missing task id |
| Exception at `afterOutboxInserted()` | Atomic writes | Single transaction | 500 | Same key may be retried | Injector mock; row counts stay 0 |
| Cross-tenant or workflow mismatch | Isolation | Lookup by authenticated tenant | `TASK_NOT_FOUND` | None | Alpha vs beta; wrong workflow id |
| Stale list after a newer retry | UI monotonic version | Keep the higher task version | Detail still shows v1 | Refresh | Vitest late-list case |
| Migration OK, app rollout fails | Compatibility | Additive V3 | Boot / Flyway validate | Revert app binary | `mvn verify` |

## 5. Verification and operations plan

- Enable supplied public contracts; add lock-interleave tests (no `Thread.sleep` as the race strategy); run Testcontainers Postgres when Docker is available; cover UI pending, 409, and stale merge.
- Log taskId, attemptId, tenantId, outcome. Never log bearer tokens.
- Keep `/actuator/health/readiness`.
- Highest residual risk: proving two transactions overlap at the database, not only at a start barrier.

## Initial-design checkpoint

- Commit hash: recorded in `SESSION_LOG.md` and final `DESIGN.md` after this file is committed.
- Checkpoint time: 2026-08-25 21:55 IST
