CREATE TABLE shot_keyframe_selection (
    project_id uuid NOT NULL,
    shot_artifact_id uuid NOT NULL,
    shot_version_id uuid NOT NULL,
    image_artifact_id uuid NOT NULL,
    image_version_id uuid NOT NULL,
    source_task_id uuid NOT NULL,
    selected_by_user_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (project_id, shot_artifact_id),
    CONSTRAINT fk_keyframe_shot FOREIGN KEY (project_id, shot_artifact_id)
        REFERENCES artifact (project_id, id),
    CONSTRAINT fk_keyframe_shot_version FOREIGN KEY (shot_artifact_id, shot_version_id)
        REFERENCES artifact_version (artifact_id, id),
    CONSTRAINT fk_keyframe_image FOREIGN KEY (project_id, image_artifact_id)
        REFERENCES artifact (project_id, id),
    CONSTRAINT fk_keyframe_image_version FOREIGN KEY (image_artifact_id, image_version_id)
        REFERENCES artifact_version (artifact_id, id),
    CONSTRAINT fk_keyframe_task FOREIGN KEY (source_task_id)
        REFERENCES task (id),
    CONSTRAINT fk_keyframe_user FOREIGN KEY (selected_by_user_id)
        REFERENCES app_user (id),
    CONSTRAINT ck_keyframe_version CHECK (version >= 0)
);

CREATE INDEX ix_keyframe_image ON shot_keyframe_selection (project_id, image_version_id);

COMMENT ON TABLE shot_keyframe_selection IS
    'Explicit human selection of one generated keyframe version for a current shot.';
