CREATE TABLE llm_provider_config_counter (
    id smallint PRIMARY KEY CHECK (id = 1),
    current_version integer NOT NULL CHECK (current_version >= 0)
);

INSERT INTO llm_provider_config_counter (id, current_version) VALUES (1, 0);

CREATE TABLE llm_provider_config (
    id uuid PRIMARY KEY,
    version integer NOT NULL UNIQUE CHECK (version > 0),
    endpoint varchar(500) NOT NULL,
    model_id varchar(160) NOT NULL,
    credential_ciphertext bytea NOT NULL,
    credential_nonce bytea NOT NULL CHECK (octet_length(credential_nonce) = 12),
    key_version integer NOT NULL CHECK (key_version > 0),
    key_mask varchar(16) NOT NULL,
    tool_calling_verified boolean NOT NULL DEFAULT false,
    active boolean NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX ux_llm_provider_config_active
    ON llm_provider_config (active) WHERE active;

COMMENT ON TABLE llm_provider_config IS
    'Immutable versioned LLM endpoint and AES-GCM encrypted credential; old versions remain for recovery.';
