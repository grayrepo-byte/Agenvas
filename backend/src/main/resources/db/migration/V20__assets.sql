CREATE TABLE asset (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    media_kind varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    object_key varchar(200) NOT NULL,
    content_type varchar(80) NOT NULL,
    byte_size bigint NOT NULL,
    sha256 char(64) NOT NULL,
    width integer,
    height integer,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_asset_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_asset_object_key UNIQUE (object_key),
    CONSTRAINT fk_asset_project FOREIGN KEY (project_id)
        REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT ck_asset_kind CHECK (media_kind IN ('IMAGE', 'VIDEO')),
    CONSTRAINT ck_asset_status CHECK (status = 'READY'),
    CONSTRAINT ck_asset_size CHECK (byte_size > 0),
    CONSTRAINT ck_asset_dimensions CHECK ((width IS NULL AND height IS NULL)
        OR (width > 0 AND height > 0)),
    CONSTRAINT ck_asset_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_asset_project_created ON asset (project_id, created_at DESC);

COMMENT ON TABLE asset IS
    'Private archived bytes. READY is inserted only after validation and atomic file move.';
