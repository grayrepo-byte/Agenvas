CREATE TABLE agent_media_approval (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    step_index integer NOT NULL,
    tool_call_id varchar(200) NOT NULL,
    operation_id uuid NOT NULL,
    request_json jsonb NOT NULL,
    target_json jsonb NOT NULL,
    task_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    result_json jsonb,
    status varchar(24) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    execution_deadline timestamptz,
    decision_key varchar(200),
    decision_hash char(64),
    notification_pending boolean NOT NULL DEFAULT true,
    CONSTRAINT uq_agent_media_approval_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_agent_media_approval_tool UNIQUE (run_id, step_index, tool_call_id),
    CONSTRAINT fk_agent_media_approval_run FOREIGN KEY (project_id, run_id)
        REFERENCES agent_run (project_id, id),
    CONSTRAINT fk_agent_media_approval_owner FOREIGN KEY (owner_id)
        REFERENCES app_user (id),
    CONSTRAINT ck_agent_media_approval_status CHECK (status IN (
        'PENDING', 'APPROVED', 'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED', 'CANCELED')),
    CONSTRAINT ck_agent_media_approval_version CHECK (version >= 0 AND step_index >= 0),
    CONSTRAINT ck_agent_media_approval_tool_call CHECK (length(btrim(tool_call_id)) > 0),
    CONSTRAINT ck_agent_media_approval_request CHECK (
        jsonb_typeof(request_json) = 'object' AND request_json->>'schemaVersion' = '1'
        AND jsonb_typeof(request_json->'outputs') = 'array'
        AND jsonb_array_length(request_json->'outputs') BETWEEN 1 AND 6),
    CONSTRAINT ck_agent_media_approval_targets CHECK (
        jsonb_typeof(target_json) = 'object' AND target_json->>'schemaVersion' = '1'
        AND jsonb_typeof(target_json->'outputs') = 'array'
        AND jsonb_array_length(target_json->'outputs') = jsonb_array_length(request_json->'outputs')),
    CONSTRAINT ck_agent_media_approval_tasks CHECK (jsonb_typeof(task_ids_json) = 'array'),
    CONSTRAINT ck_agent_media_approval_result CHECK (result_json IS NULL OR (
        jsonb_typeof(result_json) = 'object' AND result_json->>'schemaVersion' = '1')),
    CONSTRAINT ck_agent_media_approval_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_agent_media_approval_execution CHECK (
        status <> 'APPROVED' OR (execution_deadline IS NOT NULL
            AND jsonb_array_length(task_ids_json) = jsonb_array_length(request_json->'outputs'))),
    CONSTRAINT ck_agent_media_approval_decision CHECK (
        (decision_key IS NULL AND decision_hash IS NULL)
        OR (length(btrim(decision_key)) > 0 AND decision_hash ~ '^[0-9a-f]{64}$'))
);

CREATE INDEX ix_agent_media_approval_run
    ON agent_media_approval (project_id, run_id, created_at, id);
CREATE INDEX ix_agent_media_approval_outstanding
    ON agent_media_approval (id) WHERE status IN ('PENDING', 'APPROVED') OR notification_pending;
CREATE INDEX ix_agent_media_approval_tasks
    ON agent_media_approval USING gin (task_ids_json);

COMMENT ON TABLE agent_media_approval IS
    'Immutable Agent media batch and explicit user decision; approval never performs network I/O.';
