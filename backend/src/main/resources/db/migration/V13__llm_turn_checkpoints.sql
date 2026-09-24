CREATE TABLE llm_turn (
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    step_index integer NOT NULL,
    status varchar(20) NOT NULL,
    model_config_version integer NOT NULL,
    request_json jsonb NOT NULL,
    response_json jsonb,
    created_at timestamptz NOT NULL,
    responded_at timestamptz,
    PRIMARY KEY (run_id, step_index),
    CONSTRAINT fk_llm_turn_run FOREIGN KEY (project_id, run_id)
        REFERENCES agent_run (project_id, id),
    CONSTRAINT ck_llm_turn_step CHECK (step_index >= 0),
    CONSTRAINT ck_llm_turn_config_version CHECK (model_config_version > 0),
    CONSTRAINT ck_llm_turn_status CHECK (status IN ('REQUESTED', 'RESPONDED')),
    CONSTRAINT ck_llm_turn_request_object CHECK (jsonb_typeof(request_json) = 'object'),
    CONSTRAINT ck_llm_turn_response_object CHECK
        (response_json IS NULL OR jsonb_typeof(response_json) = 'object'),
    CONSTRAINT ck_llm_turn_response_state CHECK (
        (status = 'REQUESTED' AND response_json IS NULL AND responded_at IS NULL)
        OR (status = 'RESPONDED' AND response_json IS NOT NULL AND responded_at IS NOT NULL)
    )
);

CREATE INDEX ix_llm_turn_project_run ON llm_turn (project_id, run_id, step_index);

COMMENT ON TABLE llm_turn IS
    'Durable model-round checkpoint; a complete response is committed before any tool side effect.';
