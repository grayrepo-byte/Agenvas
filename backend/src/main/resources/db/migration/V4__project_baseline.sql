CREATE TABLE project (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    name varchar(120) NOT NULL,
    aspect_ratio varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    active_run_id uuid,
    event_seq bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    archived_at timestamptz,
    CONSTRAINT ck_project_name_non_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_project_aspect_ratio CHECK (
        aspect_ratio IN ('LANDSCAPE_16_9', 'PORTRAIT_9_16', 'SQUARE_1_1')
    ),
    CONSTRAINT ck_project_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_project_archive_time CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL)
    )
);

CREATE INDEX ix_project_owner_created
    ON project (owner_id, created_at DESC, id DESC);

COMMENT ON TABLE project IS
    'Permission and configuration boundary for one creative workspace.';
COMMENT ON COLUMN project.active_run_id IS
    'Reserved active Agent Run slot; foreign key is added with the run migration.';
