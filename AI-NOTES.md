# AI Use Notes

AI use or non-use is not scored. Correctness, verification, and your understanding are scored equally either way.

- **AI used:** yes
- **Tools used:** Claude Code (Anthropic) in the terminal, with shell, file, and Docker
  access to this repository. No other AI tool, and no human implementation, debugging, or
  documentation help.
- **Areas where AI assisted:**
  - Reading the supplied scaffold and reconciling it with the published contract — in
    particular noticing that the supplied React shell speaks `taskId/name/state` while the
    API speaks `id/title/status`, and that `api.js#normalizeTask` already bridges them.
  - Drafting `RetryTransaction`, `RetryService`, `RetryFingerprint`, `IdempotencyKeys`,
    `TaskRepository.lockForRetry`, and the `V3` migration.
  - Drafting the React retry flow (`requestRetry`, pending/double-click guard,
    version-guarded merge) and the Vitest and JUnit suites.
  - Drafting `DESIGN-INITIAL.md`, this file, and `DESIGN.md`.
- **Important suggestion accepted:** using `retry_attempts` as the idempotency record
  instead of adding a separate `idempotency_records` table. The supplied schema already
  carries `idempotency_key`, `request_fingerprint`, and the accepted response snapshot on
  that table, and keeping them together means a rolled-back transaction cannot leave an
  idempotency result behind — which the assignment explicitly requires.
- **Suggestion rejected or corrected:**
  - An initial draft ordered the checks as *status, then version*. That makes the loser of
    a different-key race see `TASK_NOT_RETRYABLE`, which is true but tells the operator
    nothing useful. Corrected to *version, then status* so the loser is told
    `STALE_TASK_VERSION` and knows to refresh.
  - A first pass at the concurrency evidence relied on the supplied start barrier plus a
    row-count assertion. That is exactly what the assignment calls insufficient, so it was
    replaced with the `pg_blocking_pids()` technique in `RetryConcurrencyEvidenceTest`,
    which proves the contenders are parked at the contested row before the lock is released.
  - The first end-to-end evidence script shared one `/tmp/body.$$` file between two
    background subshells, so the concurrent same-key output was garbled and briefly looked
    like two replays. That was a script bug, not an implementation bug; fixed with `mktemp`
    per invocation and re-run against a freshly reset database.
- **How generated code and documentation were verified:**
  - Every claim in `DESIGN.md` §6 comes from a command actually run, quoted as produced.
  - The full suite was run with `mvn clean verify` (27 tests) and `npm test` (16 tests),
    plus `npm run build` and `docker compose config`.
  - The concurrency evidence was checked for **non-vacuousness by mutation**: deleting
    `@Lock(PESSIMISTIC_WRITE)` from `TaskRepository.lockForRetry` makes both evidence tests
    fail, and deleting the version guard from `mergeTask` makes exactly the stale-list test
    fail. Both mutations were reverted.
  - The concurrency tests were re-run four consecutive times to confirm they are
    deterministic rather than merely passing once.
  - The whole stack was booted with `docker compose up --build` and driven over HTTP by
    `evidence/http-evidence.sh`; its recorded output is `evidence/http-evidence.out`.
- **Known unverified areas:**
  - No load or soak testing, so behaviour under sustained contention is unmeasured.
  - The `pg_blocking_pids()` ordering argument assumes PostgreSQL grants a contended row
    lock in queue order. That held across every run here, but it is an observed property of
    the engine rather than something the test itself forces.
  - The React flow was exercised through Testing Library and by hand against the Compose
    stack; there is no automated browser-level end-to-end test.
