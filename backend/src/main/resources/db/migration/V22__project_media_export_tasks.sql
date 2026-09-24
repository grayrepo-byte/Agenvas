-- An export is project work after an Agent Run has ended, not a new model instruction.
ALTER TABLE task ALTER COLUMN run_id DROP NOT NULL;
ALTER TABLE task ADD CONSTRAINT ck_task_export_run_scope CHECK (
    (kind = 'MEDIA_EXPORT' AND run_id IS NULL)
    OR (kind <> 'MEDIA_EXPORT' AND run_id IS NOT NULL)
);

-- One project-local idempotency key has one immutable export input payload.
CREATE UNIQUE INDEX uq_task_project_export_key
    ON task (project_id, step_key)
    WHERE kind = 'MEDIA_EXPORT';

COMMENT ON CONSTRAINT ck_task_export_run_scope ON task IS
    'Only project-level media exports may outlive and omit an Agent Run.';
