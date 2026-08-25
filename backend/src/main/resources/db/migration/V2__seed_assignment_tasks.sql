insert into tasks (id, workflow_id, tenant_id, title, status, version, created_at, updated_at)
values
    ('task-alpha-retryable', 'workflow-alpha', 'tenant-alpha', 'Submit invoice to gateway', 'FAILED_RETRYABLE', 0, current_timestamp, current_timestamp),
    ('task-alpha-permanent', 'workflow-alpha', 'tenant-alpha', 'Validate tax identifier', 'FAILED_PERMANENT', 0, current_timestamp, current_timestamp),
    ('task-alpha-success', 'workflow-alpha', 'tenant-alpha', 'Generate document', 'SUCCEEDED', 2, current_timestamp, current_timestamp),
    ('task-beta-retryable', 'workflow-beta', 'tenant-beta', 'Submit regional document', 'FAILED_RETRYABLE', 0, current_timestamp, current_timestamp);
