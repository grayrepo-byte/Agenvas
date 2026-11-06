-- Independent account character library; no existing content is rewritten.
CREATE TABLE public.character_library_profile (
    owner_id uuid NOT NULL,
    id uuid NOT NULL,
    content_json jsonb NOT NULL,
    version bigint NOT NULL,
    archived boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT character_library_profile_pkey PRIMARY KEY (owner_id,id),
    CONSTRAINT fk_character_library_profile_owner FOREIGN KEY (owner_id) REFERENCES public.app_user(id),
    CONSTRAINT ck_character_library_profile_version CHECK (version > 0),
    CONSTRAINT ck_character_library_profile_content CHECK (content_json->>'schemaVersion' IS NOT DISTINCT FROM '1' AND jsonb_typeof(content_json->'content') IS NOT DISTINCT FROM 'object')
 );
COMMENT ON TABLE public.character_library_profile IS 'Account-owned reusable character identity and current CAS projection.';
COMMENT ON COLUMN public.character_library_profile.owner_id IS 'Authenticated account partition; never supplied by client.';
COMMENT ON COLUMN public.character_library_profile.id IS 'Stable immutable identity.';
COMMENT ON COLUMN public.character_library_profile.content_json IS 'Versioned content envelope with schemaVersion and content.';
COMMENT ON COLUMN public.character_library_profile.version IS 'Optimistic content revision.';
COMMENT ON COLUMN public.character_library_profile.archived IS 'Archive keeps all immutable revisions and existing copies.';
COMMENT ON COLUMN public.character_library_profile.created_at IS 'Creation time from injected clock.';
COMMENT ON COLUMN public.character_library_profile.updated_at IS 'Last committed change time.';
COMMENT ON CONSTRAINT character_library_profile_pkey ON public.character_library_profile IS 'Account partition and stable identity.';
COMMENT ON INDEX public.character_library_profile_pkey IS 'Account partition and stable identity.';
COMMENT ON CONSTRAINT fk_character_library_profile_owner ON public.character_library_profile IS 'Existing account owns the profile.';
COMMENT ON CONSTRAINT ck_character_library_profile_version ON public.character_library_profile IS 'Positive content CAS.';
COMMENT ON CONSTRAINT ck_character_library_profile_content ON public.character_library_profile IS 'Explicit profile document schema.';

CREATE TABLE public.character_library_revision (
    owner_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    version bigint NOT NULL,
    content_json jsonb NOT NULL,
    archived boolean NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT character_library_revision_pkey PRIMARY KEY (owner_id,profile_id,version),
    CONSTRAINT fk_character_library_revision_profile FOREIGN KEY (owner_id,profile_id) REFERENCES public.character_library_profile(owner_id,id),
    CONSTRAINT ck_character_library_revision_version CHECK (version > 0),
    CONSTRAINT ck_character_library_revision_content CHECK (content_json->>'schemaVersion' IS NOT DISTINCT FROM '1' AND jsonb_typeof(content_json->'content') IS NOT DISTINCT FROM 'object')
 );
COMMENT ON TABLE public.character_library_revision IS 'Immutable account character content revisions.';
COMMENT ON COLUMN public.character_library_revision.owner_id IS 'Authenticated account partition; never supplied by client.';
COMMENT ON COLUMN public.character_library_revision.profile_id IS 'Stable account character identity.';
COMMENT ON COLUMN public.character_library_revision.version IS 'Optimistic content revision.';
COMMENT ON COLUMN public.character_library_revision.content_json IS 'Versioned content envelope with schemaVersion and content.';
COMMENT ON COLUMN public.character_library_revision.archived IS 'Archive keeps all immutable revisions and existing copies.';
COMMENT ON COLUMN public.character_library_revision.created_at IS 'Creation time from injected clock.';
COMMENT ON CONSTRAINT character_library_revision_pkey ON public.character_library_revision IS 'Exact owned content revision.';
COMMENT ON INDEX public.character_library_revision_pkey IS 'Exact owned content revision.';
COMMENT ON CONSTRAINT fk_character_library_revision_profile ON public.character_library_revision IS 'Revision stays in its account aggregate.';
COMMENT ON CONSTRAINT ck_character_library_revision_version ON public.character_library_revision IS 'Positive exact revision.';
COMMENT ON CONSTRAINT ck_character_library_revision_content ON public.character_library_revision IS 'Explicit revision document schema.';

CREATE TABLE public.character_library_media (
    owner_id uuid NOT NULL,
    id uuid NOT NULL,
    kind varchar(8) NOT NULL,
    metadata_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT character_library_media_pkey PRIMARY KEY (owner_id,id),
    CONSTRAINT fk_character_library_media_owner FOREIGN KEY (owner_id) REFERENCES public.app_user(id),
    CONSTRAINT ck_character_library_media_kind CHECK (kind IN ('IMAGE','AUDIO')),
    CONSTRAINT ck_character_library_media_schema CHECK (metadata_json->>'schemaVersion' IS NOT DISTINCT FROM '1')
 );
COMMENT ON TABLE public.character_library_media IS 'Independently archived immutable image or audio; never depends on source project or entry.';
COMMENT ON COLUMN public.character_library_media.owner_id IS 'Authenticated account partition; never supplied by client.';
COMMENT ON COLUMN public.character_library_media.id IS 'Stable immutable identity.';
COMMENT ON COLUMN public.character_library_media.kind IS 'Validated operation or media kind.';
COMMENT ON COLUMN public.character_library_media.metadata_json IS 'Private immutable verified media metadata; never returned publicly.';
COMMENT ON COLUMN public.character_library_media.created_at IS 'Creation time from injected clock.';
COMMENT ON CONSTRAINT character_library_media_pkey ON public.character_library_media IS 'Owned immutable archive identity.';
COMMENT ON INDEX public.character_library_media_pkey IS 'Owned immutable archive identity.';
COMMENT ON CONSTRAINT fk_character_library_media_owner ON public.character_library_media IS 'Existing account owns the independent bytes.';
COMMENT ON CONSTRAINT ck_character_library_media_kind ON public.character_library_media IS 'Character references exclude video.';
COMMENT ON CONSTRAINT ck_character_library_media_schema ON public.character_library_media IS 'Verified archive metadata schema.';

CREATE TABLE public.character_library_command (
    owner_id uuid NOT NULL,
    id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    kind varchar(12) NOT NULL,
    input_json jsonb NOT NULL,
    status varchar(12) NOT NULL,
    epoch bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    result_json jsonb,
    error_code varchar(80),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT character_library_command_pkey PRIMARY KEY (owner_id,id),
    CONSTRAINT fk_character_library_command_owner FOREIGN KEY (owner_id) REFERENCES public.app_user(id),
    CONSTRAINT ck_character_library_command_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_character_library_command_kind CHECK (kind IN ('SAVE','ARCHIVE','MEDIA','PUBLISH','IMPORT')),
    CONSTRAINT ck_character_library_command_status CHECK (status IN ('ACCEPTED','ARCHIVING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_character_library_command_lease CHECK ((status = 'ARCHIVING') = (lease_until IS NOT NULL) AND epoch >= 0),
    CONSTRAINT ck_character_library_command_input CHECK (input_json->>'schemaVersion' IS NOT DISTINCT FROM '1')
 );
COMMENT ON TABLE public.character_library_command IS 'Durable role transfer and synchronous mutation receipts with lease fencing.';
COMMENT ON COLUMN public.character_library_command.owner_id IS 'Authenticated account partition; never supplied by client.';
COMMENT ON COLUMN public.character_library_command.id IS 'Stable immutable identity.';
COMMENT ON COLUMN public.character_library_command.request_hash IS 'Canonical operation and target payload digest.';
COMMENT ON COLUMN public.character_library_command.kind IS 'Validated operation or media kind.';
COMMENT ON COLUMN public.character_library_command.input_json IS 'Fixed input and stable planned IDs; no live source lookup after acceptance.';
COMMENT ON COLUMN public.character_library_command.status IS 'Durable operation state.';
COMMENT ON COLUMN public.character_library_command.epoch IS 'Monotonic lease fencing token.';
COMMENT ON COLUMN public.character_library_command.lease_until IS 'Worker lease deadline; absent outside ARCHIVING.';
COMMENT ON COLUMN public.character_library_command.result_json IS 'Original successful public response for exact replay.';
COMMENT ON COLUMN public.character_library_command.error_code IS 'Stable sanitized failure code.';
COMMENT ON COLUMN public.character_library_command.created_at IS 'Creation time from injected clock.';
COMMENT ON COLUMN public.character_library_command.updated_at IS 'Last committed change time.';
COMMENT ON CONSTRAINT character_library_command_pkey ON public.character_library_command IS 'Client command identity is account scoped.';
COMMENT ON INDEX public.character_library_command_pkey IS 'Client command identity is account scoped.';
COMMENT ON CONSTRAINT fk_character_library_command_owner ON public.character_library_command IS 'Authenticated command account.';
COMMENT ON CONSTRAINT ck_character_library_command_hash ON public.character_library_command IS 'Canonical SHA-256 command digest.';
COMMENT ON CONSTRAINT ck_character_library_command_kind ON public.character_library_command IS 'Only explicit local profile and copy operations.';
COMMENT ON CONSTRAINT ck_character_library_command_status ON public.character_library_command IS 'Transfer state machine.';
COMMENT ON CONSTRAINT ck_character_library_command_lease ON public.character_library_command IS 'Fenced workers alone hold a lease.';
COMMENT ON CONSTRAINT ck_character_library_command_input ON public.character_library_command IS 'Frozen input schema.';

CREATE TABLE public.character_library_cleanup (
    owner_id uuid NOT NULL,
    id uuid NOT NULL,
    command_id uuid NOT NULL,
    payload_json jsonb NOT NULL,
    available_at timestamptz NOT NULL,
    CONSTRAINT character_library_cleanup_pkey PRIMARY KEY (owner_id,id),
    CONSTRAINT fk_character_library_cleanup_command FOREIGN KEY (owner_id,command_id) REFERENCES public.character_library_command(owner_id,id),
    CONSTRAINT ck_character_library_cleanup_payload CHECK (payload_json->>'schemaVersion' IS NOT DISTINCT FROM '1')
 );
COMMENT ON TABLE public.character_library_cleanup IS 'Persistent cleanup queue for accepted pins and rejected independent outputs.';
COMMENT ON COLUMN public.character_library_cleanup.owner_id IS 'Authenticated account partition; never supplied by client.';
COMMENT ON COLUMN public.character_library_cleanup.id IS 'Stable immutable identity.';
COMMENT ON COLUMN public.character_library_cleanup.command_id IS 'Owning persistent accepted command.';
COMMENT ON COLUMN public.character_library_cleanup.payload_json IS 'Private cleanup plan.';
COMMENT ON COLUMN public.character_library_cleanup.available_at IS 'Next eligible cleanup time.';
COMMENT ON CONSTRAINT character_library_cleanup_pkey ON public.character_library_cleanup IS 'Stable idempotent cleanup work.';
COMMENT ON INDEX public.character_library_cleanup_pkey IS 'Stable idempotent cleanup work.';
COMMENT ON CONSTRAINT fk_character_library_cleanup_command ON public.character_library_cleanup IS 'Cleanup is authorized by its persistent command.';
COMMENT ON CONSTRAINT ck_character_library_cleanup_payload ON public.character_library_cleanup IS 'Internal cleanup payload schema.';

CREATE TABLE public.project_character_library_link (
    owner_id uuid NOT NULL,
    project_id uuid NOT NULL,
    character_id uuid NOT NULL,
    library_id uuid NOT NULL,
    library_version bigint NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT project_character_library_link_pkey PRIMARY KEY (project_id,character_id),
    CONSTRAINT fk_character_library_link_character FOREIGN KEY (project_id,character_id) REFERENCES public.project_character(project_id,id) ON DELETE CASCADE,
    CONSTRAINT fk_character_library_link_revision FOREIGN KEY (owner_id,library_id,library_version) REFERENCES public.character_library_revision(owner_id,profile_id,version),
    CONSTRAINT ck_character_library_link_version CHECK (library_version > 0)
 );
COMMENT ON TABLE public.project_character_library_link IS 'Explicit project copy source revision; library edits never auto synchronize.';
COMMENT ON COLUMN public.project_character_library_link.owner_id IS 'Authenticated account partition; never supplied by client.';
COMMENT ON COLUMN public.project_character_library_link.project_id IS 'Authorized target project.';
COMMENT ON COLUMN public.project_character_library_link.character_id IS 'Independent target project character.';
COMMENT ON COLUMN public.project_character_library_link.library_id IS 'Account library source identity.';
COMMENT ON COLUMN public.project_character_library_link.library_version IS 'Last explicitly imported exact library revision.';
COMMENT ON COLUMN public.project_character_library_link.updated_at IS 'Last committed change time.';
COMMENT ON CONSTRAINT project_character_library_link_pkey ON public.project_character_library_link IS 'One explicit library source per project character.';
COMMENT ON INDEX public.project_character_library_link_pkey IS 'One explicit library source per project character.';
COMMENT ON CONSTRAINT fk_character_library_link_character ON public.project_character_library_link IS 'Project-side source follows the project copy.';
COMMENT ON CONSTRAINT fk_character_library_link_revision ON public.project_character_library_link IS 'Same-account immutable library revision.';
COMMENT ON CONSTRAINT ck_character_library_link_version ON public.project_character_library_link IS 'Positive imported library revision.';

CREATE INDEX ix_character_library_queue ON public.character_library_command(status,lease_until,created_at) WHERE status IN ('ACCEPTED','ARCHIVING');
COMMENT ON INDEX public.ix_character_library_queue IS 'Bounded recoverable command claim scan.';
CREATE INDEX ix_character_library_cleanup ON public.character_library_cleanup(available_at);
COMMENT ON INDEX public.ix_character_library_cleanup IS 'Eligible cleanup scan.';
CREATE TRIGGER character_library_revision_immutable BEFORE UPDATE OR DELETE ON public.character_library_revision FOR EACH ROW EXECUTE FUNCTION public.reject_artifact_version_mutation();
COMMENT ON TRIGGER character_library_revision_immutable ON public.character_library_revision IS 'Retain exact content used by every explicit project import.';
