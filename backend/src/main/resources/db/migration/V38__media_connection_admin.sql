ALTER TABLE media_provider_connection
    ADD COLUMN platform varchar(24) NOT NULL DEFAULT 'MOCK',
    ADD CONSTRAINT ck_media_connection_platform CHECK
        (platform IN ('MOCK', 'COMFYUI', 'OPENAI', 'ARK'));

ALTER TABLE media_provider_connection_version
    ADD COLUMN key_mask varchar(24);

CREATE TABLE media_connection_create_key (
    idempotency_key varchar(160) PRIMARY KEY,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    connection_id uuid NOT NULL REFERENCES media_provider_connection(id)
        DEFERRABLE INITIALLY DEFERRED,
    created_at timestamptz NOT NULL
);

CREATE TABLE media_capability_create_key (
    idempotency_key varchar(160) PRIMARY KEY,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    capability_id uuid NOT NULL REFERENCES media_capability(id)
        DEFERRABLE INITIALLY DEFERRED,
    created_at timestamptz NOT NULL
);
