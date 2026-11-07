CREATE TABLE custom_costume_reference (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    name varchar(160) NOT NULL,
    category varchar(100) NOT NULL DEFAULT '',
    tags_json jsonb NOT NULL,
    media_json jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
COMMENT ON TABLE custom_costume_reference IS 'Instance-shared self-created costume; independent permanent archive, soft deletion retains accepted transfer bytes';
COMMENT ON COLUMN custom_costume_reference.id IS 'Immutable self-created entry and snapshot identity';
COMMENT ON COLUMN custom_costume_reference.owner_id IS 'Internal private archive partition, never public ownership restriction';
COMMENT ON COLUMN custom_costume_reference.name IS 'Administrator-editable display name';
COMMENT ON COLUMN custom_costume_reference.category IS 'Optional classification, empty when absent';
COMMENT ON COLUMN custom_costume_reference.tags_json IS 'Ordered administrator tags';
COMMENT ON COLUMN custom_costume_reference.media_json IS 'Immutable internal PrivateMediaArchive metadata; excluded from public DTO';
COMMENT ON COLUMN custom_costume_reference.version IS 'Metadata and deletion CAS version';
COMMENT ON COLUMN custom_costume_reference.deleted_at IS 'Hidden from browsing and new commands; retained for already accepted transfers';
COMMENT ON COLUMN custom_costume_reference.created_at IS 'First publication time';
COMMENT ON COLUMN custom_costume_reference.updated_at IS 'Latest metadata mutation time';
CREATE INDEX idx_custom_costume_reference_visible ON custom_costume_reference(created_at,id) WHERE deleted_at IS NULL;
COMMENT ON INDEX idx_custom_costume_reference_visible IS 'Stable unified browsing of visible self-created costumes';
CREATE TABLE custom_costume_save_command (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    project_id uuid NOT NULL,
    command_key varchar(200) NOT NULL,
    payload_hash char(64) NOT NULL,
    input_json jsonb NOT NULL,
    result_json jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_custom_costume_save_command UNIQUE(owner_id,project_id,command_key)
);
COMMENT ON TABLE custom_costume_save_command IS 'Durable save intention with pinned immutable source and public replay result; retained independently of source project';
COMMENT ON COLUMN custom_costume_save_command.id IS 'Deterministic output archive identity for this accepted command';
COMMENT ON COLUMN custom_costume_save_command.owner_id IS 'Authenticated source project owner and archive partition';
COMMENT ON COLUMN custom_costume_save_command.project_id IS 'Source authorization and command scope; weak history reference';
COMMENT ON COLUMN custom_costume_save_command.command_key IS 'Client stable intention key';
COMMENT ON COLUMN custom_costume_save_command.payload_hash IS 'Digest of exact source selection, CAS and metadata input';
COMMENT ON COLUMN custom_costume_save_command.input_json IS 'Immutable accepted source pin, image digest and metadata snapshot';
COMMENT ON COLUMN custom_costume_save_command.result_json IS 'Immutable public response snapshot for uncertain-response retries';
COMMENT ON COLUMN custom_costume_save_command.created_at IS 'Command acceptance time';
COMMENT ON CONSTRAINT custom_costume_reference_pkey ON custom_costume_reference IS 'Unique permanent costume identity';
COMMENT ON CONSTRAINT custom_costume_reference_version_check ON custom_costume_reference IS 'Metadata CAS starts at zero and only advances';
COMMENT ON CONSTRAINT custom_costume_save_command_pkey ON custom_costume_save_command IS 'Unique accepted save and archive identity';
COMMENT ON CONSTRAINT uq_custom_costume_save_command ON custom_costume_save_command IS 'One save intention per authenticated source project and command key';
