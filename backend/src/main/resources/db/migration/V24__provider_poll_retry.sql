CREATE TABLE task_provider_poll_retry (
    task_id uuid PRIMARY KEY REFERENCES task (id) ON DELETE CASCADE,
    failure_count integer NOT NULL,
    last_error_code varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_task_provider_poll_retry_count
        CHECK (failure_count BETWEEN 1 AND 6)
);

COMMENT ON TABLE task_provider_poll_retry IS
    'Consecutive query/download/archive failures for one accepted provider request; never a submission retry.';
