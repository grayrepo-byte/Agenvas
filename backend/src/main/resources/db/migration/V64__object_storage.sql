-- Storage destinations are retained so switching the default cannot relocate old bytes.
CREATE TABLE storage_profile (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    provider varchar(20) NOT NULL CHECK (provider IN ('ALIYUN_OSS', 'TENCENT_COS', 'S3')),
    endpoint varchar(512) NOT NULL,
    region varchar(80) NOT NULL,
    bucket varchar(63) NOT NULL,
    key_prefix varchar(120) NOT NULL,
    path_style boolean NOT NULL,
    credential_version integer NOT NULL CHECK (credential_version > 0),
    credential_ciphertext bytea NOT NULL,
    credential_nonce bytea NOT NULL CHECK (octet_length(credential_nonce) = 12),
    credential_key_version integer NOT NULL CHECK (credential_key_version > 0),
    access_key_mask varchar(16) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE TABLE storage_settings (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    active_profile_id uuid REFERENCES storage_profile(id)
);
INSERT INTO storage_settings(singleton) VALUES (true);
-- A route is fixed before ingest. Metadata is checkpointed before remote upload, outside
-- the asset publication transaction; retries only archive the same verified bytes.
CREATE TABLE asset_storage_route (
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    asset_id uuid NOT NULL,
    media_kind varchar(10) NOT NULL CHECK (media_kind IN ('IMAGE', 'VIDEO', 'AUDIO')),
    profile_id uuid REFERENCES storage_profile(id),
    metadata_json jsonb,
    ready boolean NOT NULL DEFAULT false,
    PRIMARY KEY (project_id, asset_id),
    CHECK (metadata_json IS NULL OR (jsonb_typeof(metadata_json) = 'object' AND metadata_json ? 'schemaVersion' AND metadata_json->>'schemaVersion' = '1')),
    CHECK (NOT ready OR metadata_json IS NOT NULL)
);
