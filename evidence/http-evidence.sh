#!/usr/bin/env bash
# Mandatory-evidence walkthrough against the running Compose stack (http://localhost:8080).
# Run `docker compose up --build -d` first. Every response below is printed verbatim.
set -uo pipefail

BASE=http://localhost:8080
ALPHA="Authorization: Bearer tenant-alpha-token"
BETA="Authorization: Bearer tenant-beta-token"
KEY="Idempotency-Key"

show() { printf '\n=== %s ===\n' "$1"; }

retry() { # workflow task key version [auth-header]
  # A private temp file per invocation: concurrent calls below must not share one.
  local body; body="$(mktemp)"
  local code; code="$(curl -s -o "$body" -w '%{http_code}' -X POST \
    "$BASE/api/workflows/$1/tasks/$2/retry" \
    -H "${5:-$ALPHA}" -H "$KEY: $3" -H 'Content-Type: application/json' \
    -d "{\"expectedVersion\":$4}")"
  printf '%s %s\n' "$code" "$(cat "$body")"
  rm -f "$body"
}

psql_count() {
  docker compose exec -T database psql -U retry_app -d retry_control_room -tAc "$1"
}

RUN="e2e-$(date +%s)"

show '1. Accepted retry -> 202'
retry workflow-alpha task-alpha-retryable "$RUN-accept" 0

show '2. Exact replay of the same key -> 200, replayed:true, same attemptId'
retry workflow-alpha task-alpha-retryable "$RUN-accept" 0

show '3. Database counts prove exactly one attempt, audit event, and outbox record'
printf 'retry_attempts  : %s\n' "$(psql_count 'select count(*) from retry_attempts')"
printf 'audit_events    : %s\n' "$(psql_count 'select count(*) from audit_events')"
printf 'outbox_messages : %s\n' "$(psql_count 'select count(*) from outbox_messages')"
printf 'task status/ver : %s\n' "$(psql_count "select status || ' v' || version from tasks where id='task-alpha-retryable'")"
printf 'event types     : %s / %s\n' \
  "$(psql_count 'select distinct event_type from audit_events')" \
  "$(psql_count 'select distinct event_type from outbox_messages')"

show '4. Same key, different fingerprint -> 409 IDEMPOTENCY_KEY_REUSED'
retry workflow-alpha task-alpha-retryable "$RUN-accept" 1

show '5. Same key against a task that does not exist -> still 409, not 404'
retry workflow-alpha task-does-not-exist "$RUN-accept" 0

show '6. Stale version -> 409 STALE_TASK_VERSION'
retry workflow-alpha task-alpha-retryable "$RUN-stale" 0

show '7. Non-retryable task -> 409 TASK_NOT_RETRYABLE'
retry workflow-alpha task-alpha-permanent "$RUN-permanent" 0

show '8. Workflow mismatch -> 404 (identical body to a missing task)'
retry workflow-beta task-alpha-retryable "$RUN-mismatch" 0

show '9. Cross-tenant non-enumeration: tenant-beta asking for an alpha task -> 404'
retry workflow-alpha task-alpha-retryable "$RUN-crosstenant" 0 "$BETA"

show '10. Missing authentication -> 401'
curl -s -o /tmp/b -w '%{http_code}' -X POST \
  "$BASE/api/workflows/workflow-alpha/tasks/task-beta-retryable/retry" \
  -H "$KEY: $RUN-noauth" -H 'Content-Type: application/json' -d '{"expectedVersion":0}'
printf ' %s\n' "$(cat /tmp/b)"; rm -f /tmp/b

show '11. Invalid Idempotency-Key -> 400 INVALID_REQUEST'
retry workflow-beta task-beta-retryable "short7" 0 "$BETA"

show '12. Concurrent DIFFERENT keys against the same task/version -> one 202 and one 409'
retry workflow-beta task-beta-retryable "$RUN-conc-diff-a" 0 "$BETA" > /tmp/ev-a.$$ &
retry workflow-beta task-beta-retryable "$RUN-conc-diff-b" 0 "$BETA" > /tmp/ev-b.$$ &
wait
cat /tmp/ev-a.$$ /tmp/ev-b.$$; rm -f /tmp/ev-a.$$ /tmp/ev-b.$$
printf 'status codes seen: %s\n' "$(psql_count "select count(*) from retry_attempts where tenant_id='tenant-beta'") attempt(s) recorded for tenant-beta"

show '13. Test-fixture reset of the beta task, then concurrent SAME key -> one 202 and one 200'
psql_count "delete from outbox_messages where tenant_id='tenant-beta'" > /dev/null
psql_count "delete from audit_events where tenant_id='tenant-beta'" > /dev/null
psql_count "delete from retry_attempts where tenant_id='tenant-beta'" > /dev/null
psql_count "update tasks set status='FAILED_RETRYABLE', version=0 where id='task-beta-retryable'" > /dev/null
retry workflow-beta task-beta-retryable "$RUN-conc-same" 0 "$BETA" > /tmp/ev-a.$$ &
retry workflow-beta task-beta-retryable "$RUN-conc-same" 0 "$BETA" > /tmp/ev-b.$$ &
wait
cat /tmp/ev-a.$$ /tmp/ev-b.$$; rm -f /tmp/ev-a.$$ /tmp/ev-b.$$

show '14. Rollback evidence is covered by the published failure hook in the test suite'
echo 'RetryFailureInjector cannot be triggered over HTTP by design; see'
echo '  PublicContractTest#failureAfterOutboxInsertionRollsBackTheEntireChangeSet and'
echo '  RetryErrorContractTest#aRolledBackTransactionLeavesNoIdempotencyResultAndTheKeyStaysUsable'

show '15. Final counts across both tenants'
printf 'retry_attempts  : %s\n' "$(psql_count 'select count(*) from retry_attempts')"
printf 'audit_events    : %s\n' "$(psql_count 'select count(*) from audit_events')"
printf 'outbox_messages : %s\n' "$(psql_count 'select count(*) from outbox_messages')"
printf 'attempts/tenant : %s\n' "$(psql_count "select string_agg(tenant_id || '=' || n, ', ') from (select tenant_id, count(*) n from retry_attempts group by tenant_id order by tenant_id) t")"
printf 'task states     : %s\n' "$(psql_count "select string_agg(id || '=' || status || ' v' || version, ', ' order by id) from tasks")"
