-- Account-owned content is independent from project lifetime. Source UUIDs are audit hints only.
CREATE TABLE library_file (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    kind varchar(16) NOT NULL CHECK (kind IN ('IMAGE','VIDEO','AUDIO')),
    metadata_json jsonb NOT NULL CHECK (jsonb_typeof(metadata_json) = 'object'
        AND metadata_json->>'schemaVersion' = '1'),
    created_at timestamptz NOT NULL,
    UNIQUE (owner_id, id)
);
CREATE TABLE library_entry (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    category varchar(16) NOT NULL CHECK (category IN ('CHARACTER','SCENE','PROP','OTHER')),
    kind varchar(16) NOT NULL CHECK (kind IN ('TEXT','IMAGE','VIDEO','AUDIO')),
    content_schema_version integer NOT NULL DEFAULT 1 CHECK (content_schema_version = 1),
    text_content jsonb,
    file_id uuid,
    source_version_id uuid,
    source_json jsonb NOT NULL CHECK (jsonb_typeof(source_json) = 'object'),
    favorite boolean NOT NULL DEFAULT false,
    trashed_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (owner_id, id),
    UNIQUE (owner_id, source_version_id),
    FOREIGN KEY (owner_id, file_id) REFERENCES library_file(owner_id, id),
    CHECK ((kind = 'TEXT' AND file_id IS NULL AND jsonb_typeof(text_content) = 'object'
        AND text_content->>'format' IN ('PLAIN_TEXT','MARKDOWN')
        AND length(btrim(text_content->>'text')) BETWEEN 1 AND 20000)
        OR (kind <> 'TEXT' AND file_id IS NOT NULL AND text_content IS NULL))
);
CREATE INDEX ix_library_entry_list ON library_entry(owner_id, trashed_at, category, created_at DESC, id DESC);
CREATE TABLE library_command (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    command_key varchar(200) NOT NULL CHECK (length(btrim(command_key)) > 0),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    kind varchar(16) NOT NULL CHECK (kind IN ('SAVE','UPLOAD','IMPORT','REFERENCE')),
    input_json jsonb NOT NULL CHECK (jsonb_typeof(input_json) = 'object'),
    status varchar(16) NOT NULL CHECK (status IN ('ACCEPTED','ARCHIVING','SUCCEEDED','FAILED')),
    epoch bigint NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    lease_until timestamptz,
    result_json jsonb,
    error_code varchar(80),
    error_detail varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (owner_id, command_key),
    CHECK ((status = 'ARCHIVING') = (lease_until IS NOT NULL))
);
CREATE INDEX ix_library_command_claim ON library_command(status, lease_until, created_at);
CREATE TABLE library_import (
    project_id uuid NOT NULL,
    version_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    source_json jsonb NOT NULL CHECK (jsonb_typeof(source_json) = 'object'),
    PRIMARY KEY (project_id, version_id),
    FOREIGN KEY (project_id, version_id) REFERENCES artifact_version(project_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE library_command IS 'Durable local transfers; no provider submission or generation retry';
COMMENT ON TABLE library_import IS 'Immutable provenance; library deletion never cascades to project content';
