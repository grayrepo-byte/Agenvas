-- User-authored content is data, never executable code or tool authority.
CREATE TABLE creative_skill (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    title varchar(160) NOT NULL CHECK (length(btrim(title)) > 0),
    description varchar(1024) NOT NULL DEFAULT '',
    current_version_id uuid,
    trashed_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (owner_id, id)
);
CREATE INDEX ix_creative_skill_list ON creative_skill(owner_id, trashed_at, updated_at DESC, id DESC);
CREATE TABLE skill_draft (
    skill_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    content_json jsonb NOT NULL CHECK (jsonb_typeof(content_json) = 'object' AND content_json @> '{"schemaVersion":1}'::jsonb),
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (owner_id, skill_id) REFERENCES creative_skill(owner_id, id) ON DELETE CASCADE
);
CREATE TABLE skill_version (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    skill_id uuid NOT NULL,
    version_number bigint NOT NULL CHECK (version_number > 0),
    bundle_hash char(64) NOT NULL CHECK (bundle_hash ~ '^[0-9a-f]{64}$'),
    bundle_json jsonb NOT NULL CHECK (jsonb_typeof(bundle_json) = 'object' AND bundle_json @> '{"schemaVersion":1}'::jsonb),
    created_at timestamptz NOT NULL,
    UNIQUE (owner_id, skill_id, id),
    UNIQUE (owner_id, skill_id, version_number),
    FOREIGN KEY (owner_id, skill_id) REFERENCES creative_skill(owner_id, id)
);
ALTER TABLE creative_skill ADD CONSTRAINT fk_creative_skill_current_version
    FOREIGN KEY (owner_id, id, current_version_id) REFERENCES skill_version(owner_id, skill_id, id);
CREATE TABLE skill_publish_operation (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    skill_id uuid NOT NULL,
    command_key varchar(200) NOT NULL CHECK (length(btrim(command_key)) > 0),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    input_json jsonb NOT NULL CHECK (jsonb_typeof(input_json) = 'object' AND input_json @> '{"schemaVersion":1}'::jsonb),
    progress_json jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(progress_json) = 'object'),
    status varchar(16) NOT NULL CHECK (status IN ('ACCEPTED','ARCHIVING','SUCCEEDED','FAILED')),
    epoch bigint NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    lease_until timestamptz,
    result_version_id uuid,
    error_code varchar(80),
    error_detail varchar(500),
    pins_cleaned boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (owner_id, command_key),
    FOREIGN KEY (owner_id, skill_id) REFERENCES creative_skill(owner_id, id),
    FOREIGN KEY (owner_id, skill_id, result_version_id) REFERENCES skill_version(owner_id, skill_id, id),
    CHECK ((status = 'ARCHIVING') = (lease_until IS NOT NULL)),
    CHECK ((status = 'SUCCEEDED') = (result_version_id IS NOT NULL))
);
CREATE INDEX ix_skill_publish_claim ON skill_publish_operation(status, lease_until, created_at);
CREATE TABLE agent_skill_binding (
    agent_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    skill_id uuid NOT NULL,
    skill_version_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (project_id, agent_id) REFERENCES agent_instance(project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id, skill_id, skill_version_id) REFERENCES skill_version(owner_id, skill_id, id)
);
CREATE TABLE skill_install_operation (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    skill_id uuid NOT NULL,
    skill_version_id uuid NOT NULL,
    command_key varchar(200) NOT NULL CHECK (length(btrim(command_key)) > 0),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    input_json jsonb NOT NULL CHECK (jsonb_typeof(input_json) = 'object' AND input_json @> '{"schemaVersion":1}'::jsonb),
    result_json jsonb,
    status varchar(16) NOT NULL CHECK (status IN ('ACCEPTED','PREPARING','SUCCEEDED','FAILED')),
    epoch bigint NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    lease_until timestamptz,
    error_code varchar(80),
    error_detail varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (owner_id, project_id, command_key),
    UNIQUE (owner_id, project_id, skill_version_id),
    FOREIGN KEY (owner_id, skill_id, skill_version_id) REFERENCES skill_version(owner_id, skill_id, id),
    CHECK ((status = 'PREPARING') = (lease_until IS NOT NULL))
);
CREATE INDEX ix_skill_install_claim ON skill_install_operation(status, lease_until, created_at);
CREATE TABLE skill_install_command (
    owner_id uuid NOT NULL REFERENCES app_user(id),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    command_key varchar(200) NOT NULL CHECK (length(btrim(command_key)) > 0),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    operation_id uuid NOT NULL REFERENCES skill_install_operation(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, project_id, command_key)
);
CREATE TABLE skill_binding_command (
    owner_id uuid NOT NULL REFERENCES app_user(id),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    agent_id uuid NOT NULL,
    command_key varchar(200) NOT NULL CHECK (length(btrim(command_key)) > 0),
    payload_hash char(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    response_json jsonb NOT NULL CHECK (jsonb_typeof(response_json) = 'object'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, project_id, command_key),
    FOREIGN KEY (project_id, agent_id) REFERENCES agent_instance(project_id, id) ON DELETE CASCADE
);
COMMENT ON TABLE skill_version IS 'Immutable Skill body, bounded text resources and independently archived image bindings';
COMMENT ON TABLE agent_skill_binding IS 'Agent-only fixed version selection; no media node execution binding';
COMMENT ON TABLE skill_publish_operation IS 'Durable fenced local archival; no model or Provider execution';
