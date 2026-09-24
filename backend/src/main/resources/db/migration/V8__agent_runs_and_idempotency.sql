CREATE TABLE agent_run (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    agent_instance_id uuid NOT NULL,
    user_id uuid NOT NULL,
    status varchar(40) NOT NULL,
    instruction text NOT NULL,
    context_snapshot_json jsonb NOT NULL,
    policy_snapshot_json jsonb NOT NULL,
    profile_version integer NOT NULL,
    next_step_index integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT uq_agent_run_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_agent_run_project
        FOREIGN KEY (project_id) REFERENCES project (id),
    CONSTRAINT fk_agent_run_agent
        FOREIGN KEY (project_id, agent_instance_id)
        REFERENCES agent_instance (project_id, id),
    CONSTRAINT fk_agent_run_user
        FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_agent_run_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_TASKS', 'BLOCKED',
        'CANCEL_REQUESTED', 'CANCELED', 'FAILED', 'SUCCEEDED'
    )),
    CONSTRAINT ck_agent_run_instruction_not_blank CHECK (length(btrim(instruction)) > 0),
    CONSTRAINT ck_agent_run_profile_version_positive CHECK (profile_version > 0),
    CONSTRAINT ck_agent_run_next_step_non_negative CHECK (next_step_index >= 0),
    CONSTRAINT ck_agent_run_version_non_negative CHECK (version >= 0),
    CONSTRAINT ck_agent_run_completion CHECK (
        (status IN ('CANCELED', 'FAILED', 'SUCCEEDED') AND completed_at IS NOT NULL)
        OR (status NOT IN ('CANCELED', 'FAILED', 'SUCCEEDED') AND completed_at IS NULL)
    )
);

ALTER TABLE project
    ADD CONSTRAINT fk_project_active_run
    FOREIGN KEY (id, active_run_id)
    REFERENCES agent_run (project_id, id);

CREATE TABLE idempotency_record (
    principal_id uuid NOT NULL,
    scope varchar(120) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    request_hash char(64) NOT NULL,
    state varchar(24) NOT NULL,
    resource_id uuid,
    response_json jsonb,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (principal_id, scope, idempotency_key),
    CONSTRAINT fk_idempotency_principal
        FOREIGN KEY (principal_id) REFERENCES app_user (id),
    CONSTRAINT ck_idempotency_key_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT ck_idempotency_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_completion CHECK (
        (state = 'IN_PROGRESS' AND resource_id IS NULL AND response_json IS NULL)
        OR (state = 'COMPLETED' AND resource_id IS NOT NULL AND response_json IS NOT NULL)
    )
);

CREATE INDEX ix_agent_run_project_created
    ON agent_run (project_id, created_at DESC, id DESC);
CREATE INDEX ix_agent_run_status
    ON agent_run (status, updated_at)
    WHERE status NOT IN ('CANCELED', 'FAILED', 'SUCCEEDED');
CREATE INDEX ix_idempotency_expiry ON idempotency_record (expires_at);

COMMENT ON TABLE agent_run IS
    'Persistent execution lifecycle with immutable input and policy snapshots.';
COMMENT ON TABLE idempotency_record IS
    'Principal-scoped HTTP command replay record; same key with a different hash conflicts.';
