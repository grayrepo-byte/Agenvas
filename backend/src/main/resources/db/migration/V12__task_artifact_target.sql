CREATE TABLE task_artifact_target (
    task_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    artifact_id uuid NOT NULL,
    expected_current_version_id uuid NOT NULL,
    expected_artifact_version bigint NOT NULL,
    CONSTRAINT fk_task_artifact_target_task FOREIGN KEY (project_id, task_id)
        REFERENCES task (project_id, id),
    CONSTRAINT fk_task_artifact_target_artifact FOREIGN KEY (project_id, artifact_id)
        REFERENCES artifact (project_id, id),
    CONSTRAINT fk_task_artifact_target_version FOREIGN KEY
        (artifact_id, expected_current_version_id)
        REFERENCES artifact_version (artifact_id, id),
    CONSTRAINT ck_task_artifact_expected_version CHECK (expected_artifact_version >= 0)
);

COMMENT ON TABLE task_artifact_target IS
    'Immutable content selection precondition captured when a generation Task is created.';
