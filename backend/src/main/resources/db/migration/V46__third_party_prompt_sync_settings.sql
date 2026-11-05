-- A deployment's historical background downloads do not imply administrator consent.
-- Existing source flags, cache entries and in-flight leases remain unchanged.
CREATE TABLE third_party_prompt_sync_settings (
    id smallint PRIMARY KEY CHECK (id = 1),
    enabled boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
INSERT INTO third_party_prompt_sync_settings (id, enabled, version) VALUES (1, false, 0);
COMMENT ON TABLE third_party_prompt_sync_settings IS 'Administrator opt-in for scheduled third-party prompt downloads';
