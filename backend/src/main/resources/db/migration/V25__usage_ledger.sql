CREATE TABLE usage_ledger (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project (id),
    run_id uuid REFERENCES agent_run (id),
    task_id uuid REFERENCES task (id),
    operation_key varchar(180) NOT NULL UNIQUE,
    entry_type varchar(24) NOT NULL,
    quantity_json jsonb NOT NULL,
    estimated_cost numeric(18, 6),
    actual_cost numeric(18, 6),
    currency char(3),
    cost_status varchar(16) NOT NULL,
    cost_source varchar(80) NOT NULL,
    provider_config_version integer,
    workflow_version varchar(120),
    model_id varchar(160),
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_usage_entry_type CHECK (entry_type IN
        ('RESERVATION', 'SETTLEMENT', 'RELEASE')),
    CONSTRAINT ck_usage_cost_status CHECK (cost_status IN
        ('KNOWN', 'ESTIMATED', 'UNKNOWN')),
    CONSTRAINT ck_usage_unknown_amount CHECK (cost_status <> 'UNKNOWN'
        OR (estimated_cost IS NULL AND actual_cost IS NULL)),
    CONSTRAINT ck_usage_amount_nonnegative CHECK
        ((estimated_cost IS NULL OR estimated_cost >= 0)
        AND (actual_cost IS NULL OR actual_cost >= 0)),
    CONSTRAINT ck_usage_config_version CHECK
        (provider_config_version IS NULL OR provider_config_version > 0)
);

CREATE INDEX ix_usage_ledger_project_created
    ON usage_ledger (project_id, created_at DESC, id DESC);

COMMENT ON TABLE usage_ledger IS
    'Immutable, idempotent usage entries; unknown external cost is NULL, never a fabricated zero.';
