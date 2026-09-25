-- Direct media requests have no Agent Run or approval plan. Their immutable input
-- lives on Task, while the editable input remains on media_draft.
ALTER TABLE task ADD COLUMN origin varchar(24) NOT NULL DEFAULT 'AGENT';
UPDATE task SET origin = 'PROJECT_EXPORT' WHERE kind = 'MEDIA_EXPORT' AND run_id IS NULL;
ALTER TABLE task DROP CONSTRAINT ck_task_export_run_scope;
ALTER TABLE task ADD CONSTRAINT ck_task_origin_scope CHECK (
    (origin = 'AGENT' AND run_id IS NOT NULL)
    OR (origin = 'USER_DIRECT' AND run_id IS NULL AND plan_id IS NULL
        AND kind IN ('IMAGE_GENERATION', 'VIDEO_GENERATION'))
    OR (origin = 'PROJECT_EXPORT' AND run_id IS NULL AND kind = 'MEDIA_EXPORT')
);
ALTER TABLE task_artifact_target ALTER COLUMN expected_current_version_id DROP NOT NULL;
ALTER TABLE task_artifact_target DROP CONSTRAINT ck_task_artifact_target_mode;
ALTER TABLE task_artifact_target ADD CONSTRAINT ck_task_artifact_target_mode CHECK (
    (artifact_id IS NOT NULL AND output_slot_key IS NULL
        AND (expected_current_version_id IS NOT NULL OR expected_artifact_version = 0))
    OR (artifact_id IS NULL AND expected_current_version_id IS NULL
        AND output_slot_key IS NOT NULL AND length(btrim(output_slot_key)) > 0
        AND expected_artifact_version = 0)
);
CREATE UNIQUE INDEX uq_task_direct_key ON task (project_id, step_key)
    WHERE origin = 'USER_DIRECT';
CREATE INDEX ix_task_direct_project ON task (project_id, status, created_at)
    WHERE origin = 'USER_DIRECT';
-- A capability can be tuned by an administrator without changing queued work.
ALTER TABLE media_capability ADD COLUMN max_concurrent integer NOT NULL DEFAULT 3;
ALTER TABLE media_capability ADD CONSTRAINT ck_media_capability_max_concurrent
    CHECK (max_concurrent BETWEEN 1 AND 100);
