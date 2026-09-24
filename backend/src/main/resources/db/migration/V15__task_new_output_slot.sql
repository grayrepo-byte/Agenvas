ALTER TABLE task_artifact_target
    ALTER COLUMN artifact_id DROP NOT NULL,
    ALTER COLUMN expected_current_version_id DROP NOT NULL,
    ADD COLUMN output_slot_key varchar(160);

ALTER TABLE task_artifact_target
    ADD CONSTRAINT ck_task_artifact_target_mode CHECK (
        (artifact_id IS NOT NULL AND expected_current_version_id IS NOT NULL
            AND output_slot_key IS NULL)
        OR (artifact_id IS NULL AND expected_current_version_id IS NULL
            AND output_slot_key IS NOT NULL AND length(btrim(output_slot_key)) > 0
            AND expected_artifact_version = 0)
    );

COMMENT ON COLUMN task_artifact_target.output_slot_key IS
    'A named output in an approved plan; no Artifact identity exists until the result arrives.';
