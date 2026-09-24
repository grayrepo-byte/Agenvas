CREATE TABLE canvas_item (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    subject_type varchar(32) NOT NULL,
    subject_id uuid NOT NULL,
    artifact_id uuid,
    x numeric(14, 3) NOT NULL,
    y numeric(14, 3) NOT NULL,
    width numeric(14, 3) NOT NULL,
    height numeric(14, 3) NOT NULL,
    z_index integer NOT NULL,
    group_id uuid,
    locked boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_canvas_item_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_canvas_item_project
        FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT fk_canvas_item_artifact
        FOREIGN KEY (project_id, artifact_id)
        REFERENCES artifact (project_id, id),
    CONSTRAINT ck_canvas_item_subject_type CHECK (subject_type IN ('ARTIFACT', 'AGENT')),
    CONSTRAINT ck_canvas_item_artifact_subject CHECK (
        (subject_type = 'ARTIFACT' AND artifact_id = subject_id)
        OR (subject_type = 'AGENT' AND artifact_id IS NULL)
    ),
    CONSTRAINT ck_canvas_item_geometry CHECK (
        x BETWEEN -1000000 AND 1000000
        AND y BETWEEN -1000000 AND 1000000
        AND width BETWEEN 120 AND 2000
        AND height BETWEEN 80 AND 2000
        AND z_index BETWEEN -1000 AND 1000
    ),
    CONSTRAINT ck_canvas_item_version_non_negative CHECK (version >= 0)
);

CREATE INDEX ix_canvas_item_project ON canvas_item (project_id, z_index, id);
CREATE INDEX ix_canvas_item_subject ON canvas_item (project_id, subject_type, subject_id);

COMMENT ON TABLE canvas_item IS
    'Spatial presentation only; Artifact content remains in immutable ArtifactVersion rows.';
