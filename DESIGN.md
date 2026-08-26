# Final Design and As Built — Safe Retry

## 1. Final architecture and invariants

A retry click sends one `Idempotency-Key` and the version on screen.
`BearerTenantAuthenticationFilter` derives the tenant; nothing else is trusted for
identity. `RetryService` validates and fingerprints, then delegates to `RetryTransaction`,
whose one transaction owns every database effect.

```text
  Browser (untrusted)                      │ trust boundary │  Server (authoritative)
──────────────────────────────────────────────────────────────────────────────────────
  Retry click                                       BearerTenantAuthenticationFilter
   one key per click, action disabled                 → TenantPrincipal(tenantId)
   POST /api/workflows/{w}/tasks/{t}/retry            (route/body tenant never trusted)
   Idempotency-Key + {expectedVersion}                          │
   version-guarded merge of the reply                           ▼
                                                    RetryController → RetryService
                                                      IdempotencyKeys.require(grammar)
                                                      RetryFingerprint.of(tenant, w, t, v)
                                                                 │
   ╔═════════════════ ONE TRANSACTION — RetryTransaction.accept ═╪════════════════════╗
   ║ 1 select retry_attempts by (tenant, key) ─ hit ⇒ replay, or 409 on a different   ║
   ║   fingerprint. Runs BEFORE the task lookup so a reused key beats a would-be 404. ║
   ║ 2 select tasks ... FOR UPDATE  (tenant + workflow + task)  ◄── contenders queue  ║
   ║ 3 re-select retry_attempts under the lock ─ a same-key waiter sees the winner    ║
   ║ 4 guard version = expectedVersion, then status = FAILED_RETRYABLE                ║
   ║ 5 update tasks set status=RETRY_QUEUED, version=version+1 where version=expected ║
   ║ 6 insert retry_attempts  · uq(tenant,key) · uq(tenant,task,accepted_version)     ║
   ║ 7 insert audit_events    TASK_RETRY_QUEUED     (FK → retry_attempts, tasks)      ║
   ║ 8 insert outbox_messages TASK_RETRY_REQUESTED  (FK → retry_attempts)             ║
   ║ 9 RetryFailureInjector.afterOutboxInserted()  ── throws ⇒ 1-8 all roll back      ║
   ╚═══════════════════════════════════ COMMIT ═══════════════════════════════════════╝
                                    202 first · 200 replay · 409 · 404 · 400 · 401 · 500
```

| Invariant | Enforced by |
|---|---|
| Tenant/workflow isolation | **Both.** Java scopes every query to the token's tenant and the route workflow; PostgreSQL matches on `tenant_id`/`workflow_id` in the lock predicate. Missing, mismatched, and cross-tenant all return a byte-identical 404. |
| One accepted transition per task version | **PostgreSQL.** The row lock serialises contenders; `update ... where version = :expected` (JPA `@Version`) is the conditional write; `uq(tenant_id, task_id, accepted_version)` is the cross-process backstop. |
| Idempotent replay, tenant-scoped | **PostgreSQL.** `uq(tenant_id, idempotency_key)`. Java compares the fingerprint to choose replay versus 409, but the key can only be claimed once. |
| Atomic effects | **PostgreSQL.** One `@Transactional` boundary; the published failure hook fires inside it. |
| Version safety | **Both.** Java rejects a mismatch with a precise code; the conditional `UPDATE` makes the check binding even if the guard were removed. |

## 2. API, data model, and concurrency

- **Validation and responses:** key grammar and `@NotNull @PositiveOrZero
  expectedVersion` → `400`; first acceptance `202`; exact replay `200` with the original
  values. Error bodies carry exactly `status`, `code`, `message`.
- **Idempotency and in-flight replay:** the fingerprint is SHA-256 over length-prefixed
  `(tenantId, workflowId, taskId, expectedVersion)`, so no field combination collides. An
  in-flight duplicate blocks on the task row rather than polling; once the winner commits,
  step 3 finds the committed attempt and replays it.
- **Lock and version rule:** `lockForRetry` is `select ... for update`. When the lock is
  granted PostgreSQL re-evaluates the predicate against the newly committed row, so the
  waiter reads version 1, not its stale snapshot — which is why a different-key contender
  loses with `STALE_TASK_VERSION` instead of double-writing.
- **Tables and constraints:** `retry_attempts` *is* the idempotency record — it already
  carries the tenant, key, fingerprint and accepted snapshot — so no second table exists and
  a rollback cannot strand an idempotency result.
- **Migration:** `V3__retry_idempotency_constraints.sql` adds two unique constraints and
  drops the index the first makes exactly redundant. **No column is added**, so V1/V2 seed
  data, fixtures, and the reserved (unused) V100 handout still insert the published core
  columns unchanged.
- **Rollback boundary:** `RetryTransaction.accept` is the transaction; `RetryService` sits
  outside it, because a contender PostgreSQL rejects at commit time is in a dead
  transaction and must re-read committed state in a new one.

## 3. Mitigations as built

| Failure | Prevention as built | Detection | Recovery / residual risk | Evidence |
|---|---|---|---|---|
| Simultaneous retries, same or different keys | `lockForRetry` row lock + `uq_retry_attempts_tenant_key` + `uq_retry_attempts_task_version` (`V3`) | `DataIntegrityViolationException` → `IdempotencyRaceException`, logged with a key hash | `RetryTransaction.resolveLostRace` in a new transaction: replay for the same key, `409 STALE_TASK_VERSION` for a consumed version | `RetryConcurrencyEvidenceTest` (both cases, real PostgreSQL, `pg_blocking_pids()`); `PostgresContractTest`; `PublicContractTest#concurrentSameKey…`; evidence script steps 12–13 |
| Same tenant key reused with a different fingerprint | Fingerprint compared in `replayOrReject` **before** the task lookup | `409 IDEMPOTENCY_KEY_REUSED` | Client issues a new key for a new logical action | `RetryErrorContractTest#reusedKeyLosesEvenWhenTheNewTargetWouldOtherwiseBeMissing` and `#eachFingerprintFieldIsPartOfTheReuseCheck`; evidence steps 4–5 |
| Exception at `afterOutboxInserted()` | Hook called inside the transaction after all six writes | `500 INTERNAL_ERROR`, zero rows | Full rollback; the key is left free, so a later identical request is accepted as new | `PublicContractTest#failureAfterOutboxInsertionRollsBackTheEntireChangeSet`; `RetryErrorContractTest#aRolledBackTransactionLeavesNoIdempotencyResultAndTheKeyStaysUsable` |
| Cross-tenant or workflow/task mismatch | Lock predicate filters on the token's tenant **and** the route workflow | Byte-identical 404 bodies | Nothing written; no enumeration signal | `RetryErrorContractTest#missingTaskAndAnotherTenantsTaskAreIndistinguishable`, `#workflowMismatchIsConcealedAs404`; evidence steps 8–9 |
| Older task-list response arrives after a newer retry response | `mergeTask` in `App.jsx` drops any response whose version is lower than the version on screen; a list generation counter also prevents a superseded load from owning `loading` | Rejected merges are visible in the rendered version | Operator refresh re-reads authoritative state | `App.test.jsx#does not let an older task-list response overwrite a newer task version`; verified non-vacuous by deleting the guard and watching only that test fail |
| Migration succeeds but the application rollout fails | `V3` is constraint-only and backward compatible; the previous code never wrote duplicate keys | Flyway history + readiness probe (`readinessState`, `db`) | Roll the application back and leave `V3` in place; no destructive step to undo | Flyway runs V1→V3 on H2 and on a real PostgreSQL container in every `mvn verify`; Compose stack booted healthy |

## 4. Observability, deployment, and rollback

- **Metrics/alerts and correlation:** `RetryService` logs `tenantId`, `workflowId`,
  `taskId`, `attemptId`, `acceptedVersion`, `replayed`, and a truncated
  `idempotencyKeyHash` as structured key-values. Alert on the lost-race line (a spike means
  real contention) and the per-tenant `409` rate.
- **Never logged:** the bearer token, the raw `Idempotency-Key`, the fingerprint preimage,
  another tenant's identifiers, SQL, or stack traces.
- **Deployment order:** apply `V3`, then roll the application; `V3` only adds constraints,
  so both versions run against it simultaneously.
- **Smoke check and rollback:** `/actuator/health/readiness` (unauthenticated, includes
  `db`) gates the Compose backend, then evidence steps 1–3; rollback is application-only.
- **Committed outbox records during rollback:** they stay — the durable record that a retry
  was requested. Nothing in scope consumes them, so a rollback defers work rather than
  losing or duplicating it.

## 5. Plan versus reality

- **Initial-design commit:** `60ef483` (`DESIGN-INITIAL.md`, frozen 2026-08-25 21:55 IST and
  unchanged since).
- **Differences from `DESIGN-INITIAL.md`:**
  - The plan added a separate `idempotency_records` table. As built there is none:
    `retry_attempts` already carries the key, fingerprint and accepted snapshot, so it *is*
    the idempotency record — and one table means a rollback cannot strand an idempotency
    result, which the plan's second table could have.
  - The plan floated an optional `pg_advisory_xact_lock(tenant, key)`. Not built: the task
    row lock already serialises contenders, and a `(tenant, key)` lock would not have
    ordered *different-key* contenders at all.
  - The plan left the guard order open. Version is checked before status, so the loser of
    a different-key race is told `STALE_TASK_VERSION` ("refresh") rather than
    `TASK_NOT_RETRYABLE` — true, but useless to the operator.
- **Rejected approach:** resolving an in-flight duplicate by polling or by failing fast;
  blocking on the task row makes the published "second request may wait" behavior exact.
- **Highest remaining risk:** outbox records are never dispatched, so a retry is durably
  *requested*, never *executed*. The plan's own stated risk — proving two transactions
  overlap rather than merely starting together — is closed by
  `RetryConcurrencyEvidenceTest`.
- **First next step:** an outbox dispatcher with at-least-once delivery and a
  `dispatched_at` column, plus consumer-side idempotency.

## 6. Verification record

Java 21.0.12.1, Maven 3.9.16, Node 22.15.0, Docker 29.7.2. Results quoted as produced.

- **Commits:** baseline `bc8f0ba` · initial design `60ef483` · block 1 `4eec5cd`,
  `673f360`, `a470b5a` · block 2 rework `87830fe`, `297a4e4`, `aaf3cdc` · final docs commit
  and administrative SHA recorded by the evaluator at handoff.
  `PACKAGE_CONTENTS.sha256` is unchanged and verifies clean **at the baseline commit**
  (`git stash; git checkout bc8f0ba; shasum -a 256 -c PACKAGE_CONTENTS.sha256` → all 69
  entries `OK`). It necessarily reports `FAILED` against the finished solution, which is
  the documented behaviour.
- **Session mode, blocks, active time:** see `SESSION_LOG.md`.
- **Backend — `cd backend && mvn -B clean verify`:** `BUILD SUCCESS`, `Tests run: 27,
  Failures: 0, Errors: 0, Skipped: 0` (`StarterSmokeTest` 5, `PublicContractTest` 7,
  `RetryErrorContractTest` 12, `PostgresContractTest` 1, `RetryConcurrencyEvidenceTest` 2).
  All seven supplied `@Disabled` descriptors are enabled with assertions unchanged. The
  last three run against real PostgreSQL via Testcontainers (`postgres:17.6-alpine3.22`)
  and were re-run four consecutive times with no flakes.
- **Frontend — `cd frontend && npm ci && npm test && npm run build`:** `Tests 16 passed
  (16)`; `✓ built in 283ms`. `npm run test:coverage`: statements/lines/functions 100%,
  branches 89.42% (thresholds 80%).
- **Compose — `docker compose config`:** exits 0, no output under `--quiet`.
  `docker compose up --build -d` brought all three services to `healthy`;
  `evidence/http-evidence.sh` then produced `evidence/http-evidence.out`, covering every
  mandatory evidence item reachable over HTTP.
- **Non-vacuous checks:** removing `@Lock(PESSIMISTIC_WRITE)` fails both concurrency tests
  (`Tests run: 2, Failures: 2`); removing the `mergeTask` version guard fails exactly the
  stale-list test (`Tests 1 failed | 15 passed`). Both mutations were reverted.
- **Added dependencies:** `None`.
- **Incomplete or unverified:** the standardized 2:50 change request is not included (see
  `SESSION_LOG.md`). Nothing consumes `outbox_messages` — out of scope by design. No load
  testing was run, so the row-lock strategy is proven correct but not characterised under
  sustained contention.
