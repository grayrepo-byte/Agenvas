ALTER TABLE storage_settings
    ADD COLUMN llm_relay_enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN image_relay_enabled boolean NOT NULL DEFAULT true;
COMMENT ON COLUMN storage_settings.llm_relay_enabled IS 'Use configured relay for OpenAI-compatible LLM image inputs; no relay profile keeps inline inputs';
COMMENT ON COLUMN storage_settings.image_relay_enabled IS 'Use configured relay for URL-capable image generation inputs; frozen at task acceptance';
