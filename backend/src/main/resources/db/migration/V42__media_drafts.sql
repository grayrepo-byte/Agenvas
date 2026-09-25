CREATE TABLE media_draft (
    project_id uuid NOT NULL,
    artifact_id uuid NOT NULL,
    prompt text NOT NULL DEFAULT '',
    input_image_version_id uuid,
    duration_seconds integer,
    capability_id uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (project_id, artifact_id),
    CONSTRAINT fk_media_draft_artifact FOREIGN KEY (project_id, artifact_id)
        REFERENCES artifact (project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_media_draft_input FOREIGN KEY (project_id, input_image_version_id)
        REFERENCES artifact_version (project_id, id),
    CONSTRAINT fk_media_draft_capability FOREIGN KEY (capability_id)
        REFERENCES media_capability (id),
    CONSTRAINT ck_media_draft_prompt_length CHECK (length(prompt) <= 20000),
    CONSTRAINT ck_media_draft_duration CHECK
        (duration_seconds IS NULL OR duration_seconds BETWEEN 1 AND 30),
    CONSTRAINT ck_media_draft_version CHECK (version >= 0)
);

COMMENT ON TABLE media_draft IS
    'Editable IMAGE/VIDEO generation input; it is never an ArtifactVersion or archived Asset.';
