create table reference_catalog_source (
    id varchar(100) primary key,
    name varchar(160) not null,
    owner varchar(100) not null,
    repository varchar(100) not null,
    ref varchar(160) not null,
    manifest_path varchar(512) not null,
    format varchar(40) not null,
    enabled boolean not null default true,
    image_distribution_approved boolean not null default false,
    version bigint not null default 0,
    current_snapshot_id uuid,
    last_sync_status varchar(20) not null default 'NEVER',
    last_sync_error varchar(100),
    synced_at timestamptz,
    resolved_commit varchar(40),
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz not null
);
comment on table reference_catalog_source is 'Admin registered public GitHub metadata sources; manual sync only';
comment on column reference_catalog_source.id is 'Admin supplied stable source identity';
comment on column reference_catalog_source.name is 'Display name';
comment on column reference_catalog_source.owner is 'Public GitHub owner';
comment on column reference_catalog_source.repository is 'Public GitHub repository';
comment on column reference_catalog_source.ref is 'Operator selected branch tag or immutable commit';
comment on column reference_catalog_source.manifest_path is 'Repository relative JSON metadata path';
comment on column reference_catalog_source.format is 'Closed metadata adapter format';
comment on column reference_catalog_source.enabled is 'Allow manual synchronization and new imports';
comment on column reference_catalog_source.image_distribution_approved is 'Separate operator confirmation of image distribution rights';
comment on column reference_catalog_source.version is 'Source settings CAS and sync fencing version';
comment on column reference_catalog_source.current_snapshot_id is 'Last successfully published immutable snapshot';
comment on column reference_catalog_source.last_sync_status is 'Safe synchronization status';
comment on column reference_catalog_source.last_sync_error is 'Stable sanitized synchronization failure code';
comment on column reference_catalog_source.synced_at is 'Last successful synchronization time';
comment on column reference_catalog_source.resolved_commit is 'Last successfully resolved immutable commit';
comment on column reference_catalog_source.lease_token is 'Manual sync fencing token';
comment on column reference_catalog_source.lease_until is 'Bounded sync claim expiration';
comment on column reference_catalog_source.created_at is 'Creation time';
create table reference_catalog_snapshot (
    id uuid primary key,
    source_id varchar(100) not null references reference_catalog_source(id),
    commit_sha varchar(40) not null,
    manifest_hash varchar(64) not null,
    license varchar(100) not null,
    entries_json jsonb not null,
    created_at timestamptz not null
);
comment on table reference_catalog_snapshot is 'Immutable safe catalog metadata; previous ready snapshot survives failure';
comment on column reference_catalog_snapshot.id is 'Immutable local snapshot identity';
comment on column reference_catalog_snapshot.source_id is 'Owning catalog source';
comment on column reference_catalog_snapshot.commit_sha is 'Pinned public GitHub commit';
comment on column reference_catalog_snapshot.manifest_hash is 'Exact downloaded metadata SHA256';
comment on column reference_catalog_snapshot.license is 'Pack license claim; does not grant operator permission';
comment on column reference_catalog_snapshot.entries_json is 'Validated normalized metadata schema version one';
comment on column reference_catalog_snapshot.created_at is 'Publication time';
create table reference_catalog_import (
    id uuid primary key,
    owner_id uuid not null references app_user(id),
    project_id uuid not null references project(id),
    command_key varchar(200) not null,
    payload_hash varchar(64) not null,
    input_json jsonb not null,
    result_json jsonb,
    created_at timestamptz not null
);
comment on table reference_catalog_import is 'Durable exact image import replay without provider execution';
comment on column reference_catalog_import.id is 'Durable fixed import command and asset identity seed';
comment on column reference_catalog_import.owner_id is 'Authenticated project owner';
comment on column reference_catalog_import.project_id is 'Import destination';
comment on column reference_catalog_import.command_key is 'Owner project scoped replay key';
comment on column reference_catalog_import.payload_hash is 'Canonical fixed input fingerprint';
comment on column reference_catalog_import.input_json is 'Accepted immutable snapshot and selected image provenance';
comment on column reference_catalog_import.result_json is 'Committed exact project image version and asset identifiers';
comment on column reference_catalog_import.created_at is 'Acceptance time';
create unique index reference_catalog_import_replay on reference_catalog_import(owner_id,project_id,command_key);
comment on index reference_catalog_import_replay is 'Serialize owner project command replay identity';
create index reference_catalog_snapshot_source on reference_catalog_snapshot(source_id);
comment on index reference_catalog_snapshot_source is 'Read retained source snapshots';
alter table reference_catalog_source add constraint reference_catalog_source_format check (format in ('OPEN_POSE_COSTUME','AGENVAS_REFERENCE_PACK'));
comment on constraint reference_catalog_source_format on reference_catalog_source is 'Only reviewed bounded metadata adapters';
alter table reference_catalog_source add constraint reference_catalog_source_version check (version >= 0);
comment on constraint reference_catalog_source_version on reference_catalog_source is 'Nonnegative source CAS version';
create function reject_reference_catalog_snapshot_mutation() returns trigger language plpgsql as $$ begin raise exception 'Reference catalog snapshots are immutable'; end; $$;
comment on function reject_reference_catalog_snapshot_mutation() is 'Reject catalog metadata rewrite after publication';
create trigger reference_catalog_snapshot_immutable before update or delete on reference_catalog_snapshot for each row execute function reject_reference_catalog_snapshot_mutation();
comment on trigger reference_catalog_snapshot_immutable on reference_catalog_snapshot is 'Preserve accepted fixed image provenance';
comment on constraint reference_catalog_source_pkey on reference_catalog_source is 'Stable unique row identity';
comment on constraint reference_catalog_snapshot_pkey on reference_catalog_snapshot is 'Stable unique row identity';
comment on constraint reference_catalog_snapshot_source_id_fkey on reference_catalog_snapshot is 'Retain owning domain scope';
comment on constraint reference_catalog_import_pkey on reference_catalog_import is 'Stable unique row identity';
comment on constraint reference_catalog_import_owner_id_fkey on reference_catalog_import is 'Retain owning domain scope';
comment on constraint reference_catalog_import_project_id_fkey on reference_catalog_import is 'Retain owning domain scope';

comment on index reference_catalog_source_pkey is 'Unique stable catalog row identity';
comment on index reference_catalog_snapshot_pkey is 'Unique stable catalog row identity';
comment on index reference_catalog_import_pkey is 'Unique stable catalog row identity';
