ALTER TABLE task ADD COLUMN cancel_requested boolean NOT NULL DEFAULT false;

CREATE TABLE provider_attempt (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    task_id uuid NOT NULL,
    lease_epoch bigint NOT NULL,
    status varchar(24) NOT NULL,
    request_key uuid NOT NULL,
    provider_request_id varchar(240),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_provider_attempt_task FOREIGN KEY (project_id, task_id)
        REFERENCES task (project_id, id),
    CONSTRAINT uq_provider_attempt_epoch UNIQUE (task_id, lease_epoch),
    CONSTRAINT uq_provider_attempt_request_key UNIQUE (request_key),
    CONSTRAINT ck_provider_attempt_status CHECK (status IN ('SUBMITTING', 'ACCEPTED', 'UNKNOWN'))
);

CREATE TABLE task_late_result (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    task_id uuid NOT NULL,
    lease_epoch bigint NOT NULL,
    output_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_task_late_result_task FOREIGN KEY (project_id, task_id)
        REFERENCES task (project_id, id),
    CONSTRAINT uq_task_late_result_epoch UNIQUE (task_id, lease_epoch)
);

CREATE INDEX ix_task_submitting_lease ON task (lease_until, id)
    WHERE status = 'SUBMITTING';
CREATE INDEX ix_provider_attempt_task ON provider_attempt (task_id, created_at);

COMMENT ON TABLE provider_attempt IS
    'Submission ledger written before any external request; ambiguous outcomes remain UNKNOWN.';
COMMENT ON TABLE task_late_result IS
    'Results arriving after Run cancellation; never selected or used to promote dependents.';
