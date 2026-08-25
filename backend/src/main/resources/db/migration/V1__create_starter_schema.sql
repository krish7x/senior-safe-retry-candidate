create table tasks (
    id varchar(100) primary key,
    workflow_id varchar(100) not null,
    tenant_id varchar(100) not null,
    title varchar(200) not null,
    status varchar(40) not null,
    version bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_tasks_status check (
        status in ('FAILED_RETRYABLE', 'FAILED_PERMANENT', 'RETRY_QUEUED', 'RUNNING', 'SUCCEEDED')
    )
);

create index ix_tasks_tenant_id on tasks (tenant_id, id);
create index ix_tasks_tenant_workflow on tasks (tenant_id, workflow_id);

create table retry_attempts (
    id varchar(36) primary key,
    tenant_id varchar(100) not null,
    workflow_id varchar(100) not null,
    task_id varchar(100) not null,
    task_title varchar(200) not null,
    accepted_status varchar(40) not null,
    accepted_version bigint not null,
    idempotency_key varchar(120) not null,
    request_fingerprint varchar(64) not null,
    created_at timestamp with time zone not null,
    constraint fk_retry_attempt_task foreign key (task_id) references tasks (id)
);

create index ix_retry_attempt_lookup on retry_attempts (tenant_id, idempotency_key);
create index ix_retry_attempt_task on retry_attempts (tenant_id, task_id, created_at);

create table audit_events (
    id varchar(36) primary key,
    tenant_id varchar(100) not null,
    task_id varchar(100) not null,
    attempt_id varchar(36) not null,
    event_type varchar(60) not null,
    created_at timestamp with time zone not null,
    constraint fk_audit_task foreign key (task_id) references tasks (id),
    constraint fk_audit_attempt foreign key (attempt_id) references retry_attempts (id)
);

create index ix_audit_tenant_task on audit_events (tenant_id, task_id, created_at);

create table outbox_messages (
    id varchar(36) primary key,
    tenant_id varchar(100) not null,
    aggregate_id varchar(100) not null,
    attempt_id varchar(36) not null,
    event_type varchar(60) not null,
    payload varchar(1000) not null,
    created_at timestamp with time zone not null,
    constraint fk_outbox_attempt foreign key (attempt_id) references retry_attempts (id)
);

create index ix_outbox_created on outbox_messages (created_at, id);

-- V1 and V2 are supplied, applied migrations. Add concurrency/idempotency changes in
-- new additive migrations numbered V3 through V99; V100 is reserved for the timed handout.
