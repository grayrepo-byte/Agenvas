-- Additive; existing projects, drafts, encrypted credentials and immutable histories survive.
ALTER TABLE media_provider_connection DROP CONSTRAINT ck_media_connection_platform;
ALTER TABLE media_provider_connection ADD CONSTRAINT ck_media_connection_platform
    CHECK (platform IN ('LOCAL','MOCK','COMFYUI','OPENAI','GOOGLE','ARK','VOLCENGINE','RUNNINGHUB'));
ALTER TABLE canvas_item_media_input DROP CONSTRAINT ck_canvas_item_media_input_role;
ALTER TABLE canvas_item_media_input ADD CONSTRAINT ck_canvas_item_media_input_role
    CHECK (input_role IN ('REFERENCE','START_FRAME','END_FRAME','AUDIO_REFERENCE','VIDEO_REFERENCE'));
COMMENT ON TABLE canvas_item_media_input IS
    'Deduplicated exact media versions; dynamicValues maps named capability slots to these version identities.';
ALTER TABLE task ADD COLUMN provider_result_manifest jsonb;
ALTER TABLE task ADD CONSTRAINT ck_task_provider_result_manifest CHECK (
    provider_result_manifest IS NULL OR
    COALESCE((jsonb_typeof(provider_result_manifest) = 'object'
        AND provider_result_manifest ->> 'schemaVersion' = '1'
        AND jsonb_typeof(provider_result_manifest -> 'results') = 'array'
        AND jsonb_array_length(provider_result_manifest -> 'results') BETWEEN 1 AND 16), false));
COMMENT ON COLUMN task.provider_result_manifest IS
    'Private immutable provider result checkpoint. Never exposed in task DTOs, SSE or project export.';
