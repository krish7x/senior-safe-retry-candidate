# Final Design and As Built — Safe Retry

Complete this after the initial-design checkpoint. Keep the architecture description and as-built update together under **800 words**, excluding the diagram and table. Preserve `DESIGN-INITIAL.md` unchanged so an evaluator can compare intent with implementation.

## 1. Final architecture and invariants

Summarize the implemented request-to-database path. Include a small diagram with the trust and transaction boundaries. State the invariants actually enforced and identify whether each is enforced by Java, PostgreSQL, or both.

## 2. API, data model, and concurrency

- Final validation and response behavior:
- Idempotency scope, fingerprint, and in-flight replay behavior:
- Task lock/conditional update and version rule:
- Tables, foreign keys, unique constraints, and important indexes:
- Additive V3–V99 migrations and canonical-table/default compatibility:
- Transaction and rollback boundary:

## 3. Mitigations as built

Reference concrete code, migrations, tests, metrics, or recovery actions. Mark planned-but-unimplemented controls honestly.

| Failure | Prevention as built | Detection | Recovery/residual risk | Evidence |
|---|---|---|---|---|
| Simultaneous retries, using either the same or different keys |  |  |  |  |
| Same tenant key reused with a different fingerprint |  |  |  |  |
| Exception at `afterOutboxInserted()` |  |  |  |  |
| Cross-tenant or workflow/task mismatch |  |  |  |  |
| Older task-list response arrives after a newer retry response |  |  |  |  |
| Migration succeeds but the application rollout fails |  |  |  |  |

## 4. Observability, deployment, and rollback

- Metrics/alerts and correlation fields:
- Sensitive data that must not be logged:
- Migration/mixed-version deployment order:
- Smoke check and rollback path:
- Treatment of committed outbox records during rollback:

## 5. Plan versus reality

- Initial-design commit:
- Important differences from `DESIGN-INITIAL.md` and why:
- Rejected approach/trade-off:
- Highest remaining risk:
- First next step before production:

## 6. Verification record

- Repository baseline, initial-design, base-at-2:50, and change-at-3:25 commit SHAs (the evaluator records the administrative final SHA):
- Session mode, active blocks, and total active time (also recorded in `SESSION_LOG.md`):
- Backend command and actual result:
- PostgreSQL integration command and actual result:
- Frontend test/build commands and actual results:
- Compose command and actual result:
- Added dependencies and justification, or `None`:
- Incomplete or unverified requirements:
