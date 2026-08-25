# Senior Software Developer Work Sample

## The Double-Retry Incident

Two users retried the same failed document task at nearly the same time. The system accepted both requests and created duplicate retry work.

Make one logical retry safe from the React interface through the Java API and PostgreSQL. The base scope ends when one retry attempt, one audit event, and one outbox record have committed atomically. Do not add unrelated capabilities.

This is a synthetic assessment. It contains no production code, customer data, credentials, or real compliance rules.

## Required Git repository

This is a coding assignment. Your final submission must be a private Git repository with its complete assessment history. An edited folder or source-only archive is not a complete submission.

Before the timed session, without designing or changing the solution:

1. extract this package into a new directory;
2. initialize Git and create an unchanged baseline commit;
3. push that commit to a new private repository on the Git hosting service agreed with the evaluator;
4. grant the evaluator read access; and
5. send the repository URL and baseline commit SHA to the evaluator.

Repository creation, the unchanged baseline commit, and access setup do not count toward the four hours. All assessment work must then happen in that repository during declared active time. Do not amend, squash, rebase away, delete, or force-push assessment checkpoint history; record corrections in new commits. Keep the supplied stack and directory structure. Do not replace the starter with another project or language. Do not delete, disable, weaken, or bypass supplied contract assertions. You may enable disabled contract descriptors and make implementation-specific test-harness changes while preserving their published behavior; add new tests for your solution. New dependencies are allowed only when necessary and must be justified in final `DESIGN.md`.

`PACKAGE_CONTENTS.sha256` authenticates the unchanged starter at the baseline commit. Do not edit, delete, or regenerate it after implementation changes; the evaluator checks it by checking out the baseline commit, not against the final solution.

## Session rules

- Hard limit: **4 cumulative active hours**.
- One continuous four-hour session is encouraged.
- AI assistants, internet search, and documentation are allowed.
- Work individually. Human implementation, debugging, or documentation help is not allowed during or between active blocks.
- AI use or non-use earns no points by itself. Correctness, verification, and your ability to explain the submission are scored.
- Stop at the time limit and record unfinished work. Extra time and extra features receive no credit.
- Keep `SESSION_LOG.md` current using wall-clock times with a time zone and cumulative active time.
- A standardized, separately timed change request will be released at cumulative active time **2:50**.

### Approved two-block option

If you cannot work continuously, choose this option with the evaluator before starting. It carries no scoring penalty.

- Use no more than two active working blocks and one formal pause.
- Complete all four active hours within 24 wall-clock hours of the first start.
- Declare the planned blocks before the session. The first block must be at least 60 minutes.
- A formal pause is allowed only before cumulative active time 2:50.
- Before pausing, update `SESSION_LOG.md`, commit and push all current work with the cumulative time in the commit message, and notify the evaluator.
- During the pause, do not code, test, research, prompt an AI tool, edit documentation, or perform other assignment work.
- Notify the evaluator before resuming and record the resumed block in `SESSION_LOG.md`.
- Ordinary short breaks do not stop the active clock. Undeclared gaps count as active time.
- After the change request is released at 2:50, the remaining 70 active minutes must be continuous. Evaluator-controlled handoff or confirmed environment downtime is recorded and does not consume active time.

An unexpected emergency must be reported immediately. Do not continue working during an unrecorded interruption. The evaluator will apply the same written accommodation or equivalent replacement-change process used for other candidates.

Suggested allocation:

| Cumulative active time | Activity |
|---|---|
| 0:00–0:25 | Preflight and initial design |
| 0:25–2:50 | Base implementation and focused tests |
| 2:50–3:25 | Standardized change request |
| 3:25–4:00 | Verification and final as-built update |

At cumulative active time 0:25, save the initial plan in `DESIGN-INITIAL.md`, create a checkpoint commit, and push it. Record that commit in final `DESIGN.md`. After the checkpoint, keep `DESIGN-INITIAL.md` unchanged.

## Working scaffold

The supplied scaffold starts and its baseline checks pass before your changes. It contains:

- Java 21, Spring Boot, PostgreSQL, and Flyway;
- React using JavaScript, Vite, Vitest, and Testing Library;
- a fake bearer-token authentication filter;
- tenant-scoped task list/detail endpoints and seeded data;
- Docker Compose with health checks and non-root application containers;
- repository/test seams for the retry operation; and
- a test-only failure hook described below.

Do not rebuild supplied authentication, task listing, project setup, or container infrastructure. Preserve the working baseline and change Docker only if your implementation requires it.

### Start and verify

From the package root:

```bash
cp .env.example .env
# Set POSTGRES_PASSWORD in .env to any local-only value.
docker compose up --build
```

The Compose application is available at `http://localhost:8080`; `/api` is proxied to the backend. For local development, the backend uses port `8080` and Vite uses `5173`.

Documented checks are:

```bash
cd backend && mvn verify
cd frontend && npm ci && npm test && npm run build
docker compose config
```

Use Java 21 and Node 22 or newer. The PostgreSQL integration check requires Docker.

### Schema and migration compatibility

Treat the supplied `V1` and `V2` migrations as already applied and immutable. Base-assignment database changes must be additive migrations numbered **V3 through V99**.

Flyway **V100 is reserved for the standardized 2:50 handout**. Do not create or renumber a base migration to V100. This reservation discloses only the compatibility slot, not the later requirement.

During this session, preserve the supplied canonical table names and every existing core column in `tasks`, `retry_attempts`, `audit_events`, and `outbox_messages`. You may add tables, columns, constraints, and indexes. If you add a `NOT NULL` column to a canonical table, give it a compatible database default so the supplied seed data, test fixtures, and timed handout can still insert the published core columns. Do not require an undisclosed parent/config row for those supplied inserts.

### Seeded identities and tasks

The server derives the tenant from the bearer token. A tenant identifier supplied in a body, query, or route is never authoritative.

| Token | Tenant |
|---|---|
| `tenant-alpha-token` | `tenant-alpha` |
| `tenant-beta-token` | `tenant-beta` |

| Task | Workflow | Owner | Status | Version |
|---|---|---|---|---:|
| `task-alpha-retryable` | `workflow-alpha` | Alpha | `FAILED_RETRYABLE` | 0 |
| `task-alpha-permanent` | `workflow-alpha` | Alpha | `FAILED_PERMANENT` | 0 |
| `task-alpha-success` | `workflow-alpha` | Alpha | `SUCCEEDED` | 2 |
| `task-beta-retryable` | `workflow-beta` | Beta | `FAILED_RETRYABLE` | 0 |

## Published API contract

The supplied list endpoint remains unchanged:

```http
GET /api/tasks
Authorization: Bearer tenant-alpha-token
```

```json
{
  "tasks": [
    {
      "id": "task-alpha-retryable",
      "workflowId": "workflow-alpha",
      "title": "Submit invoice to gateway",
      "status": "FAILED_RETRYABLE",
      "version": 0
    }
  ]
}
```

`GET /api/tasks/{taskId}` returns the same task object shape. Missing and cross-tenant task IDs both return the safe `404` shape below.

Implement:

```http
POST /api/workflows/{workflowId}/tasks/{taskId}/retry
Authorization: Bearer tenant-alpha-token
Idempotency-Key: retry-abc-123
Content-Type: application/json

{"expectedVersion": 0}
```

The first accepted request returns `202 Accepted`. Success and replay responses use exactly these top-level fields:

```json
{
  "id": "task-alpha-retryable",
  "workflowId": "workflow-alpha",
  "title": "Submit invoice to gateway",
  "status": "RETRY_QUEUED",
  "version": 1,
  "attemptId": "opaque-attempt-id",
  "replayed": false
}
```

An exact replay returns `200 OK` with the original accepted values and `"replayed": true`.

Errors use exactly this top-level shape:

```json
{
  "status": 409,
  "code": "STALE_TASK_VERSION",
  "message": "Safe human-readable message"
}
```

| Situation | HTTP status | `code` |
|---|---:|---|
| Invalid JSON/body, missing header, or invalid key | 400 | `INVALID_REQUEST` |
| Missing authentication | 401 | `UNAUTHORIZED` |
| Missing task, workflow mismatch, or another tenant's task | 404 | `TASK_NOT_FOUND` |
| Non-retryable task | 409 | `TASK_NOT_RETRYABLE` |
| Stale version | 409 | `STALE_TASK_VERSION` |
| Key reused with a different request fingerprint | 409 | `IDEMPOTENCY_KEY_REUSED` |
| Unexpected server failure | 500 | `INTERNAL_ERROR` |

Messages need not match a hidden string. They must not expose stack traces, SQL, authentication material, or another tenant's existence.

## Idempotency and transaction rules

An `Idempotency-Key` must match `[A-Za-z0-9][A-Za-z0-9._:-]{7,119}`: 8–120 ASCII characters. It is unique within the authenticated tenant, not globally.

The request fingerprint is the canonical combination of authenticated tenant ID, route `workflowId`, route `taskId`, and numeric `expectedVersion`. For one logical browser action, generate one key and reuse it only when retrying that identical HTTP request. A later deliberate retry is a new logical action and uses a new key.

For the first accepted request, one PostgreSQL transaction must:

1. find the task by authenticated tenant, workflow, and task ID;
2. require `FAILED_RETRYABLE` and the supplied version;
3. set `RETRY_QUEUED` and increment the version;
4. insert exactly one retry attempt;
5. insert exactly one `TASK_RETRY_QUEUED` audit event;
6. insert exactly one `TASK_RETRY_REQUESTED` outbox record; and
7. persist the request fingerprint and original result for replay.

If the supplied `RetryFailureInjector.afterOutboxInserted()` throws, all seven effects must roll back. This is the one required fault-injection point.

PostgreSQL constraints and transaction/locking semantics must arbitrate correctness across processes. Java `exists` checks alone are insufficient.

- Two concurrent identical requests using one key must resolve to one `202` and one `200`, with the same result and one set of records. The second request may wait for the first transaction.
- Two concurrent requests using different keys against the same task/version must resolve to one `202` and one `409`.
- Reusing a tenant's key with any different fingerprint field returns `409`, even if the new target would otherwise be missing.
- If the first transaction rolls back, it leaves no idempotency result; a later request may be accepted normally.

## Required frontend flow

Complete the supplied task interface so it:

- lists the authenticated tenant's tasks;
- shows **Retry** only for `FAILED_RETRYABLE`;
- creates one key per click, disables the action while pending, and prevents a double-click request;
- displays success, conflict, and unexpected-error states; and
- replaces a task with the successful server response without a full reload.

One stale-response case is mandatory: a task-list request starts, the retry response then updates that task to version 1, and the older list response later returns version 0. The older result must not overwrite version 1. Comparing task versions, request generations, or an equivalent deterministic technique is acceptable.

Keep the supplied keyboard behavior and narrow-width layout usable. Visual redesign is not required.

## Mandatory evidence

- Accepted retry and exact replay.
- Same key with a different fingerprint.
- Stale version/non-retryable state.
- Workflow mismatch and cross-tenant non-enumeration.
- Deterministic concurrent same-key and different-key requests.
- Rollback using the published failure hook.
- Database counts proving exactly one attempt, audit event, and outbox record.
- UI pending/double-click, success/conflict, and the published stale-list race.
- `DESIGN-INITIAL.md`, final `DESIGN.md`, and `AI-NOTES.md`.
- Actual commands/results, time spent, and incomplete items.

Real sleeps are not an acceptable concurrency strategy. The supplied start-barrier contract tests are behavior descriptors and smoke checks: they release calls at about the same time, but they do **not** prove that transactions overlap at the contested database boundary. Enabling those tests alone is not deterministic concurrency evidence.

Add implementation-specific controlled-interleaving evidence for the same-key and different-key cases. For example, use a test-only hook/latch at the lookup-or-write boundary, hold a known database lock while coordinating the contender, or use an equivalent repeatable mechanism that proves which operation waits or loses. A start barrier may be part of that harness, but it is not sufficient by itself.

## Stretch work

Extra UI polish, broad refactoring, additional endpoints, production schedulers, exhaustive error permutations, and container optimization are stretch work. They receive credit only after mandatory correctness and evidence. No production deployment is required.

## Final verification

Before cumulative active time 4:00, complete `SESSION_LOG.md` and record commands you actually ran in `DESIGN.md`; do not claim tests or checks you did not run. At 4:00, stop editing every file. An incomplete but truthfully verified submission is preferable to unsupported claims.

Immediately after the stop, a maximum 10-minute administrative-only handoff window is allowed to run `git status`, commit the files exactly as they stood at 4:00, push the final commit, and send its SHA to the evaluator. Do not edit, format, generate, test, or clean files during this handoff. The pushed repository is the submission, so assessment work left outside the final commit is not evaluated. A confirmed hosting/network outage is recorded by the evaluator and extends only this administrative handoff, never implementation time.
