# Initial Design — Safe Retry

Complete this file before coding and checkpoint it at 0:25. Keep it unchanged after the checkpoint. Use concise bullets; the whole document must stay under **800 words**, excluding the diagram and table.

## 1. Scope and invariants

- Base scope:
- Out of scope:
- Invariants that must always hold:

Include tenant/workflow isolation, one accepted state transition, idempotent replay, atomic database effects, and version safety.

## 2. Request-to-database design

Add a small diagram. Mark the browser/API trust boundary, authenticated tenant context, transaction boundary, lock or conditional update, database constraints, and the point where the transaction commits.

```text
Browser -> API -> Retry service -> PostgreSQL
                                  | task
                                  | attempt
                                  | audit
                                  ` outbox
```

## 3. API, data, and transaction choices

- Validation and safe error mapping:
- Idempotency scope, fingerprint, and replay behavior:
- Task transition and concurrency control:
- Tables, foreign keys, uniqueness, and supporting indexes:
- Additive V3–V99 migration and canonical-table compatibility:
- Transaction and rollback boundary:

Name the PostgreSQL guarantee behind each correctness claim. Do not rely only on an application-level pre-check.

## 4. Failure mitigation

Give a concrete, implementation-linked answer for all six rows. A short code location, constraint, test, metric, or recovery action is stronger than generic prose.

| Failure | Invariant at risk | Prevention | Detection | Recovery | Planned evidence |
|---|---|---|---|---|---|
| Simultaneous retries, using either the same or different keys |  |  |  |  |  |
| Same tenant key reused with a different fingerprint |  |  |  |  |  |
| Exception at `afterOutboxInserted()` |  |  |  |  |  |
| Cross-tenant or workflow/task mismatch |  |  |  |  |  |
| Older task-list response arrives after a newer retry response |  |  |  |  |  |
| Migration succeeds but the application rollout fails |  |  |  |  |  |

## 5. Verification and operations plan

- Focused tests and fault injection:
- Safe structured-log fields and fields that must never be logged:
- Health/smoke signals:
- Migration compatibility and rollback approach:
- Highest residual risk:

## Initial-design checkpoint

- Commit hash:
- Checkpoint time:
