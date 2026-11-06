create table reference_catalog_private_image (
    id uuid primary key,
    owner_id uuid not null references app_user(id),
    source_id varchar(100) not null references reference_catalog_source(id),
    snapshot_id uuid not null references reference_catalog_snapshot(id),
    entry_id varchar(100) not null,
    variant_id varchar(100) not null,
    repository_path varchar(512) not null,
    blob_sha varchar(40),
    byte_size bigint,
    sha256 varchar(64),
    width integer,
    height integer,
    enabled boolean not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint reference_catalog_private_image_selection unique(owner_id,snapshot_id,entry_id,variant_id),
    constraint reference_catalog_private_image_integrity check((sha256 is null and width is null and height is null) or (sha256 is not null and blob_sha is not null and byte_size is not null and width is not null and height is not null and sha256 ~ '^[a-f0-9]{64}$' and blob_sha ~ '^[a-f0-9]{40}$' and byte_size>0 and width>0 and height>0))
);
create table reference_catalog_private_command (
    owner_id uuid not null references app_user(id),
    command_key varchar(200) not null,
    payload_hash varchar(64) not null,
    image_id uuid not null references reference_catalog_private_image(id),
    created_at timestamptz not null,
    constraint reference_catalog_private_command_pkey primary key(owner_id,command_key)
);
comment on table reference_catalog_private_image is 'Individually selected owner-private base pose or photographic costume cache authorization and fixed integrity';
comment on table reference_catalog_private_command is 'Owner-scoped selected-image command replay; never public or bulk image permission';
comment on column reference_catalog_private_image.id is 'Unpredictable owner-private image identity';
comment on column reference_catalog_private_image.owner_id is 'Only this authenticated user can read or import the cached bytes';
comment on column reference_catalog_private_image.source_id is 'Registered source; current source enabled policy applies on every read';
comment on column reference_catalog_private_image.snapshot_id is 'Immutable catalog metadata fixing selected variant and repository commit';
comment on column reference_catalog_private_image.entry_id is 'Explicitly selected entry identity';
comment on column reference_catalog_private_image.variant_id is 'Explicitly selected single image variant identity';
comment on column reference_catalog_private_image.repository_path is 'Server-derived fixed archive path; never a browser-supplied URL';
comment on column reference_catalog_private_image.blob_sha is 'Git blob object SHA1 verified from immutable commit tree before first image read';
comment on column reference_catalog_private_image.byte_size is 'Fixed Git tree size checked against decoded downloaded bytes';
comment on column reference_catalog_private_image.sha256 is 'Immutable SHA256 observed on the first validated PNG download, never a provenance claim';
comment on column reference_catalog_private_image.width is 'Validated PNG width';
comment on column reference_catalog_private_image.height is 'Validated PNG height';
comment on column reference_catalog_private_image.enabled is 'Owner cache access is active; explicit clear deactivates even when deduplicated bytes remain';
comment on column reference_catalog_private_image.created_at is 'First individual selection time';
comment on column reference_catalog_private_image.updated_at is 'Latest explicit cache or clear time';
comment on column reference_catalog_private_command.owner_id is 'Authenticated selecting owner';
comment on column reference_catalog_private_command.command_key is 'Stable explicit action replay identity';
comment on column reference_catalog_private_command.payload_hash is 'Canonical source snapshot entry variant fingerprint';
comment on column reference_catalog_private_command.image_id is 'Private identity accepted by the command';
comment on column reference_catalog_private_command.created_at is 'Command acceptance time';
comment on constraint reference_catalog_private_image_pkey on reference_catalog_private_image is 'Unique private image identity';
comment on constraint reference_catalog_private_image_owner_id_fkey on reference_catalog_private_image is 'Retain selecting owner';
comment on constraint reference_catalog_private_image_source_id_fkey on reference_catalog_private_image is 'Retain source authorization identity';
comment on constraint reference_catalog_private_image_snapshot_id_fkey on reference_catalog_private_image is 'Retain fixed commit metadata';
comment on constraint reference_catalog_private_image_selection on reference_catalog_private_image is 'One stable owner image identity for repeated explicit selections';
comment on constraint reference_catalog_private_image_integrity on reference_catalog_private_image is 'Only complete validated image integrity can be published';
comment on constraint reference_catalog_private_command_pkey on reference_catalog_private_command is 'Owner-scoped idempotent selected-image commands';
comment on constraint reference_catalog_private_command_owner_id_fkey on reference_catalog_private_command is 'Retain owner command scope';
comment on constraint reference_catalog_private_command_image_id_fkey on reference_catalog_private_command is 'Retain accepted private image identity';
comment on index reference_catalog_private_image_pkey is 'Resolve owner-private image identity';
comment on index reference_catalog_private_image_selection is 'Reuse exact owner selection across commands';
comment on index reference_catalog_private_command_pkey is 'Resolve exact owner replay key';
create index reference_catalog_private_image_owner on reference_catalog_private_image(owner_id,created_at desc);
comment on index reference_catalog_private_image_owner is 'List only the authenticated owner private cache records';
create index reference_catalog_private_image_protected on reference_catalog_private_image(sha256) where enabled and sha256 is not null;
comment on index reference_catalog_private_image_protected is 'Protect active owner cache files during shared-cache cleanup';

alter table reference_catalog_private_image add constraint reference_catalog_private_image_proof check((blob_sha is null and byte_size is null) or (blob_sha is not null and byte_size is not null and blob_sha ~ '^[a-f0-9]{40}$' and byte_size>0 and byte_size<=20971520));
comment on constraint reference_catalog_private_image_proof on reference_catalog_private_image is 'Git proof is either absent or complete and bounded before downloading one image';
create function reject_private_reference_integrity_mutation() returns trigger language plpgsql as $$
begin
    if new.id is distinct from old.id or new.owner_id is distinct from old.owner_id or new.source_id is distinct from old.source_id
       or new.snapshot_id is distinct from old.snapshot_id or new.entry_id is distinct from old.entry_id or new.variant_id is distinct from old.variant_id
       or new.repository_path is distinct from old.repository_path
       or (old.blob_sha is not null and (new.blob_sha is distinct from old.blob_sha or new.byte_size is distinct from old.byte_size))
       or (old.sha256 is not null and (new.sha256 is distinct from old.sha256 or new.width is distinct from old.width or new.height is distinct from old.height)) then
        raise exception 'Private reference identity and observed integrity are immutable';
    end if;
    return new;
end;
$$;
comment on function reject_private_reference_integrity_mutation() is 'Freeze selected owner identity, Git proof and first validated observed SHA256 while allowing cache deactivation';
create trigger reference_catalog_private_image_immutable before update on reference_catalog_private_image for each row execute function reject_private_reference_integrity_mutation();
comment on trigger reference_catalog_private_image_immutable on reference_catalog_private_image is 'Prevent cache replay or concurrency from changing accepted image integrity';
