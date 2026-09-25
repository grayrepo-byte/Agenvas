CREATE TABLE media_provider_connection (
    id uuid PRIMARY KEY,
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    enabled boolean NOT NULL,
    version bigint NOT NULL CHECK (version >= 0),
    current_version integer NOT NULL CHECK (current_version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE media_provider_connection_version (
    connection_id uuid NOT NULL REFERENCES media_provider_connection(id),
    version integer NOT NULL CHECK (version > 0),
    origin varchar(500),
    origin_sha256 char(64),
    credential_ciphertext bytea,
    credential_nonce bytea,
    credential_key_version integer,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (connection_id, version),
    CONSTRAINT ck_media_connection_origin_hash CHECK
        (origin_sha256 IS NULL OR origin_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_media_connection_credential CHECK
        ((credential_ciphertext IS NULL AND credential_nonce IS NULL
            AND credential_key_version IS NULL)
         OR (credential_ciphertext IS NOT NULL AND credential_nonce IS NOT NULL
            AND credential_key_version > 0))
);

CREATE TABLE media_capability (
    id uuid PRIMARY KEY,
    connection_id uuid NOT NULL REFERENCES media_provider_connection(id),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    enabled boolean NOT NULL,
    version bigint NOT NULL CHECK (version >= 0),
    current_version integer NOT NULL CHECK (current_version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_media_capability_connection_id UNIQUE (connection_id, id)
);

CREATE TABLE media_capability_version (
    capability_id uuid NOT NULL REFERENCES media_capability(id),
    version integer NOT NULL CHECK (version > 0),
    adapter_id varchar(80) NOT NULL CHECK (length(btrim(adapter_id)) > 0),
    mapping_sha256 char(64) NOT NULL CHECK (mapping_sha256 ~ '^[0-9a-f]{64}$'),
    spec_json jsonb NOT NULL CHECK (jsonb_typeof(spec_json) = 'object'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (capability_id, version)
);

CREATE TABLE media_default (
    kind varchar(40) PRIMARY KEY CHECK (kind IN ('IMAGE_GENERATION', 'VIDEO_GENERATION')),
    capability_id uuid NOT NULL REFERENCES media_capability(id),
    version bigint NOT NULL CHECK (version >= 0)
);

ALTER TABLE plan_step
    ADD COLUMN capability_id uuid,
    ADD COLUMN capability_version integer,
    ADD COLUMN connection_id uuid,
    ADD COLUMN connection_version integer,
    ADD COLUMN mapping_sha256 char(64),
    ADD CONSTRAINT ck_plan_step_media_binding CHECK (
        (capability_id IS NULL AND capability_version IS NULL AND connection_id IS NULL
            AND connection_version IS NULL AND mapping_sha256 IS NULL)
        OR (capability_id IS NOT NULL AND capability_version IS NOT NULL
            AND connection_id IS NOT NULL AND connection_version IS NOT NULL
            AND mapping_sha256 ~ '^[0-9a-f]{64}$')),
    ADD CONSTRAINT fk_plan_step_media_capability FOREIGN KEY (capability_id, capability_version)
        REFERENCES media_capability_version(capability_id, version),
    ADD CONSTRAINT fk_plan_step_media_connection FOREIGN KEY (connection_id, connection_version)
        REFERENCES media_provider_connection_version(connection_id, version),
    ADD CONSTRAINT fk_plan_step_media_owner FOREIGN KEY (connection_id, capability_id)
        REFERENCES media_capability(connection_id, id);

ALTER TABLE task
    ADD COLUMN capability_id uuid,
    ADD COLUMN capability_version integer,
    ADD COLUMN connection_id uuid,
    ADD COLUMN connection_version integer,
    ADD CONSTRAINT ck_task_media_binding CHECK (
        (capability_id IS NULL AND capability_version IS NULL AND connection_id IS NULL
            AND connection_version IS NULL)
        OR (capability_id IS NOT NULL AND capability_version IS NOT NULL
            AND connection_id IS NOT NULL AND connection_version IS NOT NULL)),
    ADD CONSTRAINT fk_task_media_capability FOREIGN KEY (capability_id, capability_version)
        REFERENCES media_capability_version(capability_id, version),
    ADD CONSTRAINT fk_task_media_connection FOREIGN KEY (connection_id, connection_version)
        REFERENCES media_provider_connection_version(connection_id, version),
    ADD CONSTRAINT fk_task_media_owner FOREIGN KEY (connection_id, capability_id)
        REFERENCES media_capability(connection_id, id);

ALTER TABLE provider_attempt
    ADD COLUMN capability_id uuid,
    ADD COLUMN capability_version integer,
    ADD COLUMN connection_id uuid,
    ADD COLUMN connection_version integer,
    ADD CONSTRAINT ck_provider_attempt_media_binding CHECK (
        (capability_id IS NULL AND capability_version IS NULL AND connection_id IS NULL
            AND connection_version IS NULL)
        OR (capability_id IS NOT NULL AND capability_version IS NOT NULL
            AND connection_id IS NOT NULL AND connection_version IS NOT NULL)),
    ADD CONSTRAINT fk_provider_attempt_media_capability FOREIGN KEY (capability_id, capability_version)
        REFERENCES media_capability_version(capability_id, version),
    ADD CONSTRAINT fk_provider_attempt_media_connection FOREIGN KEY (connection_id, connection_version)
        REFERENCES media_provider_connection_version(connection_id, version),
    ADD CONSTRAINT fk_provider_attempt_media_owner FOREIGN KEY (connection_id, capability_id)
        REFERENCES media_capability(connection_id, id);

INSERT INTO media_provider_connection (id, name, enabled, version, current_version, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000101', 'Mock', true, 0, 1, now(), now());
INSERT INTO media_provider_connection_version
    (connection_id, version, origin, origin_sha256, created_at)
VALUES ('00000000-0000-4000-8000-000000000101', 1, NULL, NULL, now());
INSERT INTO media_capability
    (id, connection_id, name, enabled, version, current_version, created_at, updated_at)
VALUES
    ('00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000101',
        'Mock image', true, 0, 1, now(), now()),
    ('00000000-0000-4000-8000-000000000103', '00000000-0000-4000-8000-000000000101',
        'Mock video', true, 0, 1, now(), now());
INSERT INTO media_capability_version
    (capability_id, version, adapter_id, mapping_sha256, spec_json, created_at)
VALUES
    ('00000000-0000-4000-8000-000000000102', 1, 'MOCK_IMAGE',
        '7f2f6b237c227a8fd7b0a2c12f59fdf5edbeda554d4fde5b602245e4a6edb1e2',
        '{"schemaVersion":1,"kind":"IMAGE_GENERATION"}', now()),
    ('00000000-0000-4000-8000-000000000103', 1, 'MOCK_VIDEO',
        '17f04ba5441f7695f98e465cbacc8d7965d763d263762f464888158831434934',
        '{"schemaVersion":1,"kind":"VIDEO_GENERATION","minimumSeconds":1,"maximumSeconds":30}', now());
INSERT INTO media_default (kind, capability_id, version)
VALUES
    ('IMAGE_GENERATION', '00000000-0000-4000-8000-000000000102', 0),
    ('VIDEO_GENERATION', '00000000-0000-4000-8000-000000000103', 0);
