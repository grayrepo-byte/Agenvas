CREATE TABLE tool_execution (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    step_index integer NOT NULL,
    tool_call_id varchar(200) NOT NULL,
    tool_name varchar(120) NOT NULL,
    argument_hash char(64) NOT NULL,
    status varchar(20) NOT NULL,
    result_json jsonb,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT fk_tool_execution_turn FOREIGN KEY (run_id, step_index)
        REFERENCES llm_turn (run_id, step_index),
    CONSTRAINT fk_tool_execution_run FOREIGN KEY (project_id, run_id)
        REFERENCES agent_run (project_id, id),
    CONSTRAINT uq_tool_execution_call UNIQUE (run_id, step_index, tool_call_id),
    CONSTRAINT ck_tool_execution_step CHECK (step_index >= 0),
    CONSTRAINT ck_tool_execution_status CHECK (status IN ('EXECUTING', 'COMPLETED')),
    CONSTRAINT ck_tool_execution_result CHECK (
        (status = 'EXECUTING' AND result_json IS NULL AND completed_at IS NULL)
        OR (status = 'COMPLETED' AND jsonb_typeof(result_json) = 'object'
            AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_tool_execution_run ON tool_execution (project_id, run_id, status);

COMMENT ON TABLE tool_execution IS
    'One caller-driven tool invocation. Reservation, business mutation and result commit together.';
