-- System model diagnostics have no project/run/task identity. Keep every business call scoped.
ALTER TABLE call_log ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE call_log DROP CONSTRAINT call_log_operation_check;
ALTER TABLE call_log ADD CONSTRAINT call_log_operation_check
    CHECK (operation IN ('CHAT', 'SUBMIT', 'POLL', 'DIAGNOSTIC'));
ALTER TABLE call_log DROP CONSTRAINT ck_call_log_operation_scope;
ALTER TABLE call_log ADD CONSTRAINT ck_call_log_operation_scope CHECK (
    (operation = 'DIAGNOSTIC' AND kind = 'LLM' AND project_id IS NULL
        AND run_id IS NULL AND task_id IS NULL AND step_index IS NULL)
    OR
    (project_id IS NOT NULL AND (
        (operation = 'CHAT' AND kind = 'LLM' AND (
            (run_id IS NOT NULL AND step_index IS NOT NULL AND step_index >= 0)
            OR (task_id IS NOT NULL AND run_id IS NULL AND step_index IS NULL)))
        OR (operation IN ('SUBMIT', 'POLL') AND kind IN ('IMAGE', 'VIDEO', 'AUDIO')
            AND task_id IS NOT NULL AND step_index IS NULL)
    ))
);
COMMENT ON COLUMN call_log.project_id IS
    'Project ownership scope for business calls; null only for global administrator LLM diagnostics.';
COMMENT ON CONSTRAINT ck_call_log_operation_scope ON call_log IS
    'Only system LLM diagnostics can omit project identity; business model/media calls retain their execution scope.';
