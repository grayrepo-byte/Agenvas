CREATE TABLE export_proposal (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    status varchar(20) NOT NULL,
    input_json jsonb NOT NULL,
    input_pins_json jsonb NOT NULL,
    proposal_hash char(64) NOT NULL,
    project_version bigint NOT NULL,
    approved_task_id uuid,
    decided_by_user_id uuid,
    created_at timestamptz NOT NULL,
    decided_at timestamptz,
    CONSTRAINT uq_export_proposal_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_export_proposal_run FOREIGN KEY (project_id, run_id)
        REFERENCES agent_run (project_id, id),
    CONSTRAINT fk_export_proposal_task FOREIGN KEY (project_id, approved_task_id)
        REFERENCES task (project_id, id),
    CONSTRAINT fk_export_proposal_decider FOREIGN KEY (decided_by_user_id)
        REFERENCES app_user (id),
    CONSTRAINT ck_export_proposal_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_export_proposal_json CHECK (jsonb_typeof(input_json) = 'object'
        AND jsonb_typeof(input_pins_json) = 'array'),
    CONSTRAINT ck_export_proposal_hash CHECK (proposal_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_export_proposal_project_version CHECK (project_version >= 0),
    CONSTRAINT ck_export_proposal_decision CHECK (
        (status = 'PENDING' AND approved_task_id IS NULL
            AND decided_by_user_id IS NULL AND decided_at IS NULL)
        OR (status = 'APPROVED' AND approved_task_id IS NOT NULL
            AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL)
        OR (status = 'REJECTED' AND approved_task_id IS NULL
            AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL))
);

CREATE INDEX ix_export_proposal_project_created
    ON export_proposal (project_id, created_at DESC, id DESC);

COMMENT ON TABLE export_proposal IS
    'Agent-suggested immutable export input; only an authenticated user may authorize a Task.';
