CREATE TABLE artifact (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    kind varchar(32) NOT NULL,
    title varchar(160) NOT NULL,
    current_version_id uuid,
    archived_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_artifact_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_artifact_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_artifact_kind
        CHECK (kind IN ('TEXT', 'IMAGE', 'VIDEO', 'CHARACTER', 'SCENE', 'SHOT')),
    CONSTRAINT ck_artifact_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_artifact_version_non_negative CHECK (version >= 0)
);

CREATE TABLE artifact_version (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    artifact_id uuid NOT NULL,
    version_no integer NOT NULL,
    schema_version integer NOT NULL,
    content_json jsonb NOT NULL,
    input_refs_json jsonb NOT NULL,
    created_by_kind varchar(32) NOT NULL,
    run_id uuid,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_artifact_version_number UNIQUE (artifact_id, version_no),
    CONSTRAINT uq_artifact_version_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_artifact_version_artifact_id UNIQUE (artifact_id, id),
    CONSTRAINT fk_artifact_version_artifact
        FOREIGN KEY (project_id, artifact_id)
        REFERENCES artifact (project_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_artifact_version_no_positive CHECK (version_no > 0),
    CONSTRAINT ck_artifact_schema_version_positive CHECK (schema_version > 0),
    CONSTRAINT ck_artifact_content_object CHECK (jsonb_typeof(content_json) = 'object'),
    CONSTRAINT ck_artifact_input_refs_array CHECK (jsonb_typeof(input_refs_json) = 'array'),
    CONSTRAINT ck_artifact_created_by_kind
        CHECK (created_by_kind IN ('USER', 'AGENT', 'TASK'))
);

ALTER TABLE artifact
    ADD CONSTRAINT fk_artifact_current_version
    FOREIGN KEY (id, current_version_id)
    REFERENCES artifact_version (artifact_id, id);

CREATE TABLE artifact_version_reference (
    source_version_id uuid NOT NULL,
    project_id uuid NOT NULL,
    target_version_id uuid NOT NULL,
    reference_role varchar(64) NOT NULL,
    reference_order integer NOT NULL,
    PRIMARY KEY (source_version_id, reference_role, reference_order),
    CONSTRAINT fk_artifact_reference_source
        FOREIGN KEY (project_id, source_version_id)
        REFERENCES artifact_version (project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_artifact_reference_target
        FOREIGN KEY (project_id, target_version_id)
        REFERENCES artifact_version (project_id, id),
    CONSTRAINT ck_artifact_reference_order_non_negative CHECK (reference_order >= 0)
);

CREATE TABLE artifact_relation (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    source_artifact_id uuid NOT NULL,
    target_artifact_id uuid NOT NULL,
    relation_type varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_artifact_relation
        UNIQUE (project_id, source_artifact_id, target_artifact_id, relation_type),
    CONSTRAINT fk_artifact_relation_source
        FOREIGN KEY (project_id, source_artifact_id)
        REFERENCES artifact (project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_artifact_relation_target
        FOREIGN KEY (project_id, target_artifact_id)
        REFERENCES artifact (project_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_artifact_relation_not_self CHECK (source_artifact_id <> target_artifact_id),
    CONSTRAINT ck_artifact_relation_type_not_blank CHECK (length(btrim(relation_type)) > 0)
);

CREATE INDEX ix_artifact_project_active
    ON artifact (project_id, archived_at, created_at DESC);
CREATE INDEX ix_artifact_version_history
    ON artifact_version (artifact_id, version_no DESC);
CREATE INDEX ix_artifact_reference_target
    ON artifact_version_reference (project_id, target_version_id);

CREATE FUNCTION reject_artifact_version_mutation() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'artifact_version rows are immutable';
END;
$$;

CREATE TRIGGER artifact_version_no_update
    BEFORE UPDATE ON artifact_version
    FOR EACH ROW EXECUTE FUNCTION reject_artifact_version_mutation();

CREATE TRIGGER artifact_version_no_delete
    BEFORE DELETE ON artifact_version
    FOR EACH ROW EXECUTE FUNCTION reject_artifact_version_mutation();

COMMENT ON TABLE artifact_version IS
    'Immutable content revisions. Selection changes update artifact.current_version_id only.';
COMMENT ON TABLE artifact_version_reference IS
    'Normalized semantic version references used to enforce same-project referential integrity.';
