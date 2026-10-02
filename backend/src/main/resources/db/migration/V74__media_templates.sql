-- Templates are immutable image snapshots plus prompt text, never generation recipes.
CREATE TABLE media_template_image (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    metadata_json jsonb NOT NULL CHECK (jsonb_typeof(metadata_json) = 'object'
        AND metadata_json->>'schemaVersion' = '1' AND metadata_json->>'kind' = 'IMAGE'),
    created_at timestamptz NOT NULL
);
CREATE TABLE media_template (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    scope varchar(16) NOT NULL CHECK (scope IN ('PERSONAL', 'SYSTEM')),
    target_kind varchar(16) NOT NULL CHECK (target_kind IN ('IMAGE', 'VIDEO')),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    prompt varchar(20000) NOT NULL CHECK (length(btrim(prompt)) > 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX ix_media_template_list ON media_template(scope, owner_id, target_kind, updated_at DESC);
CREATE TABLE media_template_attachment (
    template_id uuid NOT NULL REFERENCES media_template(id) ON DELETE CASCADE,
    image_id uuid NOT NULL REFERENCES media_template_image(id),
    position integer NOT NULL CHECK (position BETWEEN 0 AND 7),
    PRIMARY KEY (template_id, position),
    UNIQUE (template_id, image_id)
);
-- A durable fixed snapshot makes a repeated application recoverable without another import.
CREATE TABLE media_template_import_command (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    command_key varchar(200) NOT NULL CHECK (length(btrim(command_key)) > 0),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    input_json jsonb NOT NULL CHECK (jsonb_typeof(input_json) = 'object' AND input_json->>'schemaVersion' = '1'),
    result_json jsonb CHECK (result_json IS NULL OR jsonb_typeof(result_json) = 'object'),
    created_at timestamptz NOT NULL,
    UNIQUE (project_id, command_key)
);
-- Pin image metadata while an accepted command may still be copying bytes.
CREATE TABLE media_template_import_source (
    command_id uuid NOT NULL REFERENCES media_template_import_command(id) ON DELETE CASCADE,
    image_id uuid NOT NULL REFERENCES media_template_image(id),
    PRIMARY KEY (command_id, image_id)
);
CREATE TABLE media_template_import_image (
    project_id uuid NOT NULL,
    version_id uuid NOT NULL,
    template_id uuid NOT NULL,
    template_version bigint NOT NULL CHECK (template_version >= 0),
    template_name varchar(160) NOT NULL,
    PRIMARY KEY (project_id, version_id),
    FOREIGN KEY (project_id, version_id) REFERENCES artifact_version(project_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE media_template_import_image IS 'Template provenance with no LibraryEntry dependency; template deletion never deletes project versions';
