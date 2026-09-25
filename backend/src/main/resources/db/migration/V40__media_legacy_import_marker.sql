-- Startup import runs after Flyway and before media claims. A completed marker is
-- deliberately never reset by a later change to legacy environment variables.
CREATE TABLE media_legacy_import_marker (
    id smallint PRIMARY KEY CHECK (id = 1),
    completed_at timestamptz,
    imported_connection_id uuid REFERENCES media_provider_connection(id)
);

INSERT INTO media_legacy_import_marker (id) VALUES (1);

CREATE TABLE media_legacy_origin_map (
    config_version integer PRIMARY KEY REFERENCES comfyui_config_version(config_version),
    connection_id uuid NOT NULL,
    connection_version integer NOT NULL,
    origin_sha256 char(64) NOT NULL CHECK (origin_sha256 ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY (connection_id, connection_version)
        REFERENCES media_provider_connection_version(connection_id, version)
);

CREATE INDEX ix_media_legacy_origin_map_sha
    ON media_legacy_origin_map (origin_sha256, config_version);
