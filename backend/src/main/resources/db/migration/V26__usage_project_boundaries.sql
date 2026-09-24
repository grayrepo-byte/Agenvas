ALTER TABLE usage_ledger
    DROP CONSTRAINT usage_ledger_run_id_fkey,
    DROP CONSTRAINT usage_ledger_task_id_fkey;

ALTER TABLE usage_ledger
    ADD CONSTRAINT fk_usage_run_project
        FOREIGN KEY (project_id, run_id) REFERENCES agent_run (project_id, id),
    ADD CONSTRAINT fk_usage_task_project
        FOREIGN KEY (project_id, task_id) REFERENCES task (project_id, id);

COMMENT ON CONSTRAINT fk_usage_task_project ON usage_ledger IS
    'Prevents a usage record from attributing a foreign project Task to this project.';
