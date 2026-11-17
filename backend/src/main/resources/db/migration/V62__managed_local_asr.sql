CREATE TABLE local_asr_installation (
    id integer PRIMARY KEY CHECK (id = 1),
    enabled boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    state varchar(20) NOT NULL DEFAULT 'MISSING' CHECK (state IN ('MISSING','QUEUED','DOWNLOADING','VERIFYING','READY','FAILED','CANCELED')),
    operation_id uuid,
    downloaded_bytes bigint NOT NULL DEFAULT 0 CHECK (downloaded_bytes >= 0),
    error_code varchar(64),
    lease_owner varchar(100),
    lease_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE local_asr_installation IS 'Explicit administrator consent and durable bounded local speech model download state';
COMMENT ON COLUMN local_asr_installation.id IS 'Installation singleton identity';
COMMENT ON COLUMN local_asr_installation.enabled IS 'Consent to local recognition; enabling never downloads a model';
COMMENT ON COLUMN local_asr_installation.version IS 'CAS revision for administrator commands, excluding progress';
COMMENT ON COLUMN local_asr_installation.state IS 'Durable download and verification lifecycle';
COMMENT ON COLUMN local_asr_installation.operation_id IS 'Fence for this explicitly requested download attempt';
COMMENT ON COLUMN local_asr_installation.downloaded_bytes IS 'Bytes received in this attempt, not proof of verification';
COMMENT ON COLUMN local_asr_installation.error_code IS 'Safe stable failure classification without URLs or native output';
COMMENT ON COLUMN local_asr_installation.lease_owner IS 'Bounded download worker identity';
COMMENT ON COLUMN local_asr_installation.lease_until IS 'Download lease expiry; interrupted downloads require explicit retry';
COMMENT ON COLUMN local_asr_installation.updated_at IS 'Last command or progress time';
COMMENT ON CONSTRAINT local_asr_installation_pkey ON local_asr_installation IS 'One settings record per installation';
COMMENT ON CONSTRAINT local_asr_installation_id_check ON local_asr_installation IS 'Singleton key';
COMMENT ON CONSTRAINT local_asr_installation_version_check ON local_asr_installation IS 'Positive CAS revision';
COMMENT ON CONSTRAINT local_asr_installation_state_check ON local_asr_installation IS 'Known model download states';
COMMENT ON CONSTRAINT local_asr_installation_downloaded_bytes_check ON local_asr_installation IS 'Nonnegative progress';
COMMENT ON INDEX local_asr_installation_pkey IS 'Supports singleton settings lookup';
INSERT INTO local_asr_installation (id) VALUES (1);
