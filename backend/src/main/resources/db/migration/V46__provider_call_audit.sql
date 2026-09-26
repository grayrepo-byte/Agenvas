CREATE TABLE call_log (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project(id),
    task_id uuid,
    run_id uuid,
    step_index integer,
    kind varchar(12) NOT NULL CHECK (kind IN ('LLM', 'IMAGE', 'VIDEO')),
    operation varchar(12) NOT NULL CHECK (operation IN ('CHAT', 'SUBMIT', 'POLL')),
    status varchar(12) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    provider varchar(160),
    model varchar(160),
    trace_id char(32) NOT NULL UNIQUE CHECK (trace_id ~ '^[0-9a-f]{32}$'),
    provider_request_id varchar(240),
    error_code varchar(120),
    started_at timestamptz NOT NULL,
    responded_at timestamptz,
    duration_ms bigint CHECK (duration_ms >= 0),
    mock boolean NOT NULL,
    FOREIGN KEY (project_id, task_id) REFERENCES task(project_id, id),
    FOREIGN KEY (project_id, run_id) REFERENCES agent_run(project_id, id),
    CHECK ((operation = 'CHAT' AND kind = 'LLM' AND run_id IS NOT NULL AND step_index IS NOT NULL AND step_index >= 0)
        OR (operation IN ('SUBMIT', 'POLL') AND kind IN ('IMAGE', 'VIDEO') AND task_id IS NOT NULL)),
    CHECK ((status = 'RUNNING' AND responded_at IS NULL AND duration_ms IS NULL)
        OR (status <> 'RUNNING' AND responded_at IS NOT NULL AND duration_ms IS NOT NULL))
);
CREATE INDEX ix_call_log_project_time ON call_log(project_id, started_at DESC, id DESC);
CREATE INDEX ix_call_log_task ON call_log(task_id, operation);
CREATE INDEX ix_call_log_round ON call_log(run_id, step_index) WHERE kind = 'LLM';
COMMENT ON TABLE call_log IS 'Safe metadata for individual adapter invocations; no bodies, endpoints or credentials.';
