-- Text cards may invoke the configured chat model directly without creating an Agent Run.
-- The immutable prompt and target CAS snapshot live on Task; the complete model response is
-- checkpointed in output_json while RUNNING and retained beside an internal result summary on success.
ALTER TABLE task DROP CONSTRAINT ck_task_kind;
ALTER TABLE task ADD CONSTRAINT ck_task_kind CHECK (kind IN (
    'AGENT_TURN', 'TEXT_GENERATION', 'IMAGE_GENERATION', 'VIDEO_GENERATION',
    'MEDIA_EXPORT', 'ASSET_INGEST'
));

ALTER TABLE task DROP CONSTRAINT ck_task_origin_scope;
ALTER TABLE task ADD CONSTRAINT ck_task_origin_scope CHECK (
    (origin = 'AGENT' AND run_id IS NOT NULL)
    OR (origin = 'USER_DIRECT' AND run_id IS NULL AND plan_id IS NULL
        AND kind IN ('TEXT_GENERATION', 'IMAGE_GENERATION', 'VIDEO_GENERATION'))
    OR (origin = 'PROJECT_EXPORT' AND run_id IS NULL AND kind = 'MEDIA_EXPORT')
);

ALTER TABLE call_log DROP CONSTRAINT call_log_check;
ALTER TABLE call_log ADD CONSTRAINT ck_call_log_operation_scope CHECK (
    (operation = 'CHAT' AND kind = 'LLM'
        AND ((run_id IS NOT NULL AND step_index IS NOT NULL AND step_index >= 0)
            OR (task_id IS NOT NULL AND run_id IS NULL AND step_index IS NULL)))
    OR (operation IN ('SUBMIT', 'POLL') AND kind IN ('IMAGE', 'VIDEO')
        AND task_id IS NOT NULL AND step_index IS NULL)
);
