-- Emergency per-tenant retry pause (change request: "Emergency tenant retry pause").
--
-- Additive only: no existing table or core column is changed, so V1/V2 seed data, the
-- test fixtures, and the reserved (unused) V100 handout still insert their published
-- columns unchanged. Base migrations remain in the V3-V99 band.
--
-- This table is the tenant-scoped pause gate. It is durable, so the pause survives an
-- application restart, and being a database row it arbitrates a pause against concurrent
-- retries across every application instance -- not just within one JVM.
create table tenant_retry_pause (
    tenant_id varchar(100) primary key,
    paused boolean not null default false,
    paused_at timestamp with time zone
);

-- A gate row is provisioned per tenant so the retry path always has a row to lock: the
-- retry takes a shared lock on this row and the pause takes an exclusive lock on it, which
-- is what serialises the two operations. The supplied tenants are seeded here; a
-- production system would create this row when a tenant is onboarded.
insert into tenant_retry_pause (tenant_id, paused) values ('tenant-alpha', false);
insert into tenant_retry_pause (tenant_id, paused) values ('tenant-beta', false);
