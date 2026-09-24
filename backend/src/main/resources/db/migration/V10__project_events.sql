CREATE TABLE project_event (
    project_id uuid NOT NULL,
    seq bigint NOT NULL,
    event_id uuid NOT NULL,
    type varchar(120) NOT NULL,
    schema_version integer NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    payload_json jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (project_id, seq),
    CONSTRAINT uq_project_event_id UNIQUE (event_id),
    CONSTRAINT fk_project_event_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_project_event_seq_positive CHECK (seq > 0),
    CONSTRAINT ck_project_event_type_not_blank CHECK (length(btrim(type)) > 0),
    CONSTRAINT ck_project_event_schema_version_positive CHECK (schema_version > 0),
    CONSTRAINT ck_project_event_aggregate_version_non_negative CHECK (aggregate_version >= 0),
    CONSTRAINT ck_project_event_payload_object CHECK (jsonb_typeof(payload_json) = 'object')
);

CREATE INDEX ix_project_event_occurred_at
    ON project_event (occurred_at);

COMMENT ON TABLE project_event IS
    'Per-project transactional event log and replay outbox ordered by the project counter.';
COMMENT ON COLUMN project_event.seq IS
    'Commit-safe project-local waterline allocated while holding the project row lock.';
