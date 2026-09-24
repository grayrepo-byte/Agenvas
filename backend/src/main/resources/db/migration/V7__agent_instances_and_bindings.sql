CREATE TABLE agent_instance (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    profile_key varchar(80) NOT NULL,
    profile_version integer NOT NULL,
    name varchar(120) NOT NULL,
    instruction varchar(8000) NOT NULL,
    output_group_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_agent_instance_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_agent_instance_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_profile_key_not_blank CHECK (length(btrim(profile_key)) > 0),
    CONSTRAINT ck_agent_profile_version_positive CHECK (profile_version > 0),
    CONSTRAINT ck_agent_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_agent_instruction_not_blank CHECK (length(btrim(instruction)) > 0),
    CONSTRAINT ck_agent_version_non_negative CHECK (version >= 0)
);

ALTER TABLE artifact_version
    ADD CONSTRAINT uq_artifact_version_project_artifact_id
    UNIQUE (project_id, artifact_id, id);

CREATE TABLE agent_binding (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    agent_instance_id uuid NOT NULL,
    artifact_id uuid NOT NULL,
    selected_version_id uuid NOT NULL,
    binding_type varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_agent_binding_artifact
        UNIQUE (agent_instance_id, artifact_id, binding_type),
    CONSTRAINT fk_agent_binding_agent
        FOREIGN KEY (project_id, agent_instance_id)
        REFERENCES agent_instance (project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_binding_artifact
        FOREIGN KEY (project_id, artifact_id)
        REFERENCES artifact (project_id, id),
    CONSTRAINT fk_agent_binding_version
        FOREIGN KEY (project_id, artifact_id, selected_version_id)
        REFERENCES artifact_version (project_id, artifact_id, id),
    CONSTRAINT ck_agent_binding_type CHECK (binding_type IN ('INPUT'))
);

ALTER TABLE canvas_item
    DROP CONSTRAINT ck_canvas_item_artifact_subject,
    ADD COLUMN agent_instance_id uuid,
    ADD CONSTRAINT fk_canvas_item_agent
        FOREIGN KEY (project_id, agent_instance_id)
        REFERENCES agent_instance (project_id, id),
    ADD CONSTRAINT ck_canvas_item_subject_mapping CHECK (
        (subject_type = 'ARTIFACT' AND artifact_id = subject_id AND agent_instance_id IS NULL)
        OR (subject_type = 'AGENT' AND artifact_id IS NULL AND agent_instance_id = subject_id)
    );

CREATE INDEX ix_agent_instance_project ON agent_instance (project_id, created_at, id);
CREATE INDEX ix_agent_binding_agent ON agent_binding (project_id, agent_instance_id);

COMMENT ON TABLE agent_instance IS
    'Persistent card configuration only; no thread, request principal, or mutable run context.';
COMMENT ON TABLE agent_binding IS
    'Explicit Agent input fixed to an exact immutable ArtifactVersion.';
