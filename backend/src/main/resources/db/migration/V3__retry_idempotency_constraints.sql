-- Additive concurrency and idempotency controls for the safe-retry transaction.
--
-- Canonical tables and every core column supplied by V1 are unchanged, and no
-- column is added, so V2 seed data, the test fixtures, and the reserved V100
-- handout can still insert the published core columns unchanged.
--
-- `retry_attempts` is the idempotency record: it already carries the tenant, the
-- key, the request fingerprint, and the accepted response snapshot, so no second
-- bookkeeping table is needed and a rolled-back transaction cannot leave an
-- orphaned idempotency result behind.

-- An Idempotency-Key is unique within a tenant, not globally. This is the
-- constraint that arbitrates a same-key race between two application processes.
alter table retry_attempts
    add constraint uq_retry_attempts_tenant_key unique (tenant_id, idempotency_key);

-- One accepted retry per task version. This is the database-side backstop for a
-- different-key race: two contenders cannot both record an attempt that claims to
-- have moved the same task to the same version.
alter table retry_attempts
    add constraint uq_retry_attempts_task_version unique (tenant_id, task_id, accepted_version);

-- Exactly redundant with uq_retry_attempts_tenant_key above; the unique index
-- backing that constraint serves every lookup the old index served.
drop index if exists ix_retry_attempt_lookup;
