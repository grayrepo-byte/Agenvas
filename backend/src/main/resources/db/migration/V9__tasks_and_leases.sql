CREATE TABLE task (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    plan_id uuid,
    step_key varchar(160) NOT NULL,
    kind varchar(40) NOT NULL,
    status varchar(40) NOT NULL,
    input_json jsonb NOT NULL,
    input_hash char(64) NOT NULL,
    output_json jsonb,
    provider_id uuid,
    provider_request_id varchar(240),
    attempt_no integer NOT NULL,
    next_action_at timestamptz NOT NULL,
    lease_owner varchar(160),
    lease_until timestamptz,
    lease_epoch bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    error_code varchar(120),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT uq_task_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_task_run_step_attempt UNIQUE (run_id, step_key, attempt_no),
    CONSTRAINT fk_task_project FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_task_run
        FOREIGN KEY (project_id, run_id) REFERENCES agent_run (project_id, id),
    CONSTRAINT ck_task_step_key_not_blank CHECK (length(btrim(step_key)) > 0),
    CONSTRAINT ck_task_kind CHECK (kind IN (
        'AGENT_TURN', 'IMAGE_GENERATION', 'VIDEO_GENERATION', 'MEDIA_EXPORT', 'ASSET_INGEST'
    )),
    CONSTRAINT ck_task_status CHECK (status IN (
        'PENDING', 'READY', 'RUNNING', 'SUBMITTING', 'WAITING_PROVIDER',
        'UNKNOWN', 'BLOCKED', 'SUCCEEDED', 'FAILED', 'CANCELED'
    )),
    CONSTRAINT ck_task_input_hash CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_task_attempt_positive CHECK (attempt_no > 0),
    CONSTRAINT ck_task_lease_epoch_non_negative CHECK (lease_epoch >= 0),
    CONSTRAINT ck_task_version_non_negative CHECK (version >= 0),
    CONSTRAINT ck_task_lease_pair CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)
    ),
    CONSTRAINT ck_task_completion CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CANCELED') AND completed_at IS NOT NULL)
        OR (status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED') AND completed_at IS NULL)
    )
);

CREATE TABLE task_dependency (
    project_id uuid NOT NULL,
    task_id uuid NOT NULL,
    depends_on_task_id uuid NOT NULL,
    required_output_key varchar(160),
    PRIMARY KEY (task_id, depends_on_task_id),
    CONSTRAINT fk_task_dependency_task
        FOREIGN KEY (project_id, task_id)
        REFERENCES task (project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_task_dependency_predecessor
        FOREIGN KEY (project_id, depends_on_task_id)
        REFERENCES task (project_id, id),
    CONSTRAINT ck_task_dependency_not_self CHECK (task_id <> depends_on_task_id)
);

CREATE INDEX ix_task_claim_ready
    ON task (next_action_at, created_at, id)
    WHERE status = 'READY';
CREATE INDEX ix_task_reclaim_running
    ON task (lease_until, created_at, id)
    WHERE status = 'RUNNING';
CREATE INDEX ix_task_run ON task (project_id, run_id, created_at, id);
CREATE INDEX ix_task_dependency_predecessor
    ON task_dependency (project_id, depends_on_task_id);

COMMENT ON TABLE task IS
    'Persistent recoverable work; leases fence workers and never cover provider waiting time.';
COMMENT ON COLUMN task.lease_epoch IS
    'Monotonic fencing token incremented on every claim or reclaim.';
