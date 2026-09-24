CREATE TABLE task_manual_replacement (
    original_task_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    replacement_task_id uuid NOT NULL UNIQUE,
    approved_by_user_id uuid NOT NULL REFERENCES app_user (id),
    original_task_version bigint NOT NULL,
    idempotency_key varchar(120) NOT NULL,
    confirmation_code varchar(80) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_manual_replacement_original FOREIGN KEY (project_id, original_task_id)
        REFERENCES task (project_id, id),
    CONSTRAINT fk_manual_replacement_new FOREIGN KEY (project_id, replacement_task_id)
        REFERENCES task (project_id, id),
    CONSTRAINT uq_manual_replacement_command UNIQUE
        (project_id, approved_by_user_id, idempotency_key),
    CONSTRAINT ck_manual_replacement_version CHECK (original_task_version >= 0),
    CONSTRAINT ck_manual_replacement_confirmation CHECK
        (confirmation_code = 'ACCEPT_POSSIBLE_DUPLICATE_COST')
);

COMMENT ON TABLE task_manual_replacement IS
    'One explicit-risk new Task for an unresolved original; original Task and attempt remain UNKNOWN.';
