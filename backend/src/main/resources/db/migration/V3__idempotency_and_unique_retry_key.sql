-- Additive concurrency controls. Canonical tables and core columns are unchanged.
create table idempotency_records (
    tenant_id varchar(100) not null,
    idempotency_key varchar(120) not null,
    request_fingerprint varchar(64) not null,
    attempt_id varchar(36),
    created_at timestamp with time zone not null,
    constraint pk_idempotency_records primary key (tenant_id, idempotency_key)
);

create index ix_idempotency_records_attempt on idempotency_records (attempt_id);

drop index if exists ix_retry_attempt_lookup;
create unique index uq_retry_attempts_tenant_idempotency_key
    on retry_attempts (tenant_id, idempotency_key);
