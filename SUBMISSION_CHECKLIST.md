# Candidate Session and Submission Checklist

## Before the timed session

- [ ] Create a private Git repository from the unchanged candidate package and push the baseline commit.
- [ ] Grant the evaluator access and send the repository URL and baseline commit SHA.
- [ ] Choose the continuous or approved two-block mode and agree the schedule with the evaluator.
- [ ] Do not design, code, test, research, or prompt AI before the declared start.
- [ ] Do not rewrite or force-push assessment checkpoint history; record corrections in new commits.
- [ ] Preserve `PACKAGE_CONTENTS.sha256` unchanged; it authenticates the baseline commit only.

## Before coding

- [ ] Record the session start time.
- [ ] Start `SESSION_LOG.md` with wall-clock, time-zone, and cumulative active time.
- [ ] Confirm the supplied baseline starts and its checks pass; report a package or environment fault immediately.
- [ ] Read the published API, seed, idempotency, race, and mandatory-evidence rules.
- [ ] Complete `DESIGN-INITIAL.md` within its 800-word cap.
- [ ] At cumulative 0:25, commit and push the initial design and record its hash; do not edit that file afterward.

## If using the approved two-block option

- [ ] Use at most two active blocks, one formal pause, and four cumulative active hours within 24 wall-clock hours.
- [ ] Pause only before cumulative 2:50; update `SESSION_LOG.md`, commit, push, and notify the evaluator first.
- [ ] Do no assignment-related work during the pause and notify the evaluator before resuming.
- [ ] Complete the final 70 active minutes continuously after the change is released.

## Base implementation

- [ ] Preserve server-derived tenant isolation and safe non-enumerating errors.
- [ ] Implement the version/state guard, tenant-scoped idempotency, one transaction, and database arbitration.
- [ ] Preserve the canonical tables/core columns; use additive base migrations V3–V99 and leave reserved V100 unused.
- [ ] Give every added `NOT NULL` canonical-table column a compatible default for supplied inserts.
- [ ] Cover same-key and different-key concurrency with implementation-specific controlled interleaving and no real sleeps; the supplied start barriers alone are only smoke checks.
- [ ] Prove full rollback with the published failure hook and database counts.
- [ ] Complete the required React retry flow and specified late-list race test.
- [ ] Keep the supplied build and Compose baseline working.
- [ ] Do not delete, disable, weaken, or bypass supplied contract assertions; enable descriptors or adapt only the harness while preserving behavior, and add solution tests.
- [ ] Record and justify every added dependency in final `DESIGN.md`.

## Final record

- [ ] Stop coding at the announced cumulative checkpoints and final deadline.
- [ ] Complete final `DESIGN.md` with baseline/initial hashes, as-built differences, actual active time, risks, and incomplete items.
- [ ] Complete `AI-NOTES.md`; `No AI used` is fully acceptable.
- [ ] Record only commands and results actually run.
- [ ] Work individually; do not receive human implementation, debugging, or documentation help.
- [ ] Preserve source, migrations, tests, and configuration.
- [ ] Exclude credentials, `.env`, generated output, IDE state, and unrelated personal files.
- [ ] Finish `SESSION_LOG.md` before cumulative 4:00 and stop editing every file at the deadline.
- [ ] During the maximum 10-minute administrative-only handoff, make no file changes; commit and push the exact stopped state.
- [ ] Send the evaluator the final commit SHA and confirm `git status --short` is empty.
