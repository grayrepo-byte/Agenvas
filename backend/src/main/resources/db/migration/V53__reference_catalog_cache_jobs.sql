create table reference_catalog_cache_job (
    id uuid primary key,
    owner_id uuid not null references app_user(id),
    source_id varchar(100) not null references reference_catalog_source(id),
    snapshot_id uuid not null references reference_catalog_snapshot(id),
    command_key varchar(200) not null,
    payload_hash varchar(64) not null,
    state varchar(30) not null,
    items_json jsonb not null,
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint reference_catalog_cache_job_state check(state in ('QUEUED','RUNNING','SUCCEEDED','PARTIAL_FAILED','CANCELLED')),
    constraint reference_catalog_cache_job_command unique(owner_id,command_key)
);
comment on table reference_catalog_cache_job is 'Explicit authorized image prefetch jobs; pinned immutable metadata and durable per-image progress';
comment on column reference_catalog_cache_job.id is 'Stable cache job identity';
comment on column reference_catalog_cache_job.owner_id is 'Administrator accepting the explicit prefetch command';
comment on column reference_catalog_cache_job.source_id is 'Registered source whose current rights apply to every cache access';
comment on column reference_catalog_cache_job.snapshot_id is 'Immutable snapshot selected when this job was accepted';
comment on column reference_catalog_cache_job.command_key is 'Administrator-scoped idempotent command identity';
comment on column reference_catalog_cache_job.payload_hash is 'Canonical source snapshot and selected entry fingerprint';
comment on column reference_catalog_cache_job.state is 'Durable bounded job lifecycle';
comment on column reference_catalog_cache_job.items_json is 'Schema version one selected image identities and ready or failed progress; never media bytes';
comment on column reference_catalog_cache_job.lease_token is 'Worker fencing token; stale workers cannot overwrite cancellation or retry';
comment on column reference_catalog_cache_job.lease_until is 'Claim expiry allowing restart recovery without retrying successful images';
comment on column reference_catalog_cache_job.created_at is 'Acceptance time';
comment on column reference_catalog_cache_job.updated_at is 'Latest progress or explicit control time';
comment on constraint reference_catalog_cache_job_pkey on reference_catalog_cache_job is 'Unique cache job identity';
comment on constraint reference_catalog_cache_job_owner_id_fkey on reference_catalog_cache_job is 'Retain administrator scope';
comment on constraint reference_catalog_cache_job_source_id_fkey on reference_catalog_cache_job is 'Retain registered source scope';
comment on constraint reference_catalog_cache_job_snapshot_id_fkey on reference_catalog_cache_job is 'Retain immutable metadata needed for restart';
comment on constraint reference_catalog_cache_job_state on reference_catalog_cache_job is 'Closed cache job state machine';
comment on constraint reference_catalog_cache_job_command on reference_catalog_cache_job is 'Same command key cannot create duplicate prefetch jobs';
comment on index reference_catalog_cache_job_pkey is 'Unique cache job identity';
comment on index reference_catalog_cache_job_command is 'Resolve exact administrator command replay';
create index reference_catalog_cache_job_claim on reference_catalog_cache_job(state,lease_until,created_at) where state in ('QUEUED','RUNNING');
comment on index reference_catalog_cache_job_claim is 'Bounded queue scanning and expired claim recovery';
