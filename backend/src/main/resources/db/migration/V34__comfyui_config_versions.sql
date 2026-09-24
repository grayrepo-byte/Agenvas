CREATE TABLE comfyui_config_version (
    config_version integer PRIMARY KEY,
    origin varchar(500) NOT NULL,
    origin_sha256 char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_comfyui_config_version_positive CHECK (config_version > 0),
    CONSTRAINT ck_comfyui_origin_sha256 CHECK (origin_sha256 ~ '^[0-9a-f]{64}$')
);
