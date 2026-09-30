-- Additive: existing projects, exact input identities, history and encrypted settings survive.
ALTER TABLE artifact DROP CONSTRAINT ck_artifact_kind;
ALTER TABLE artifact ADD CONSTRAINT ck_artifact_kind CHECK (kind IN ('TEXT','IMAGE','VIDEO','AUDIO'));
ALTER TABLE asset DROP CONSTRAINT ck_asset_kind;
ALTER TABLE asset ADD CONSTRAINT ck_asset_kind CHECK (media_kind IN ('IMAGE','VIDEO','AUDIO'));
ALTER TABLE asset DROP CONSTRAINT ck_asset_video_duration;
ALTER TABLE asset ADD CONSTRAINT ck_asset_media_duration CHECK (
    (media_kind = 'IMAGE' AND duration_ms IS NULL)
    OR (media_kind = 'VIDEO' AND (duration_ms IS NULL OR duration_ms BETWEEN 1 AND 60000))
    OR (media_kind = 'AUDIO' AND duration_ms IS NOT NULL AND duration_ms BETWEEN 1 AND 600000 AND width IS NULL AND height IS NULL));
ALTER TABLE task DROP CONSTRAINT ck_task_kind;
ALTER TABLE task ADD CONSTRAINT ck_task_kind CHECK (kind IN
    ('AGENT_TURN','TEXT_GENERATION','IMAGE_GENERATION','VIDEO_GENERATION','AUDIO_GENERATION','ASSET_INGEST'));
ALTER TABLE task DROP CONSTRAINT ck_task_origin_scope;
ALTER TABLE task ADD CONSTRAINT ck_task_origin_scope CHECK (
    (origin = 'AGENT' AND run_id IS NOT NULL)
    OR (origin = 'USER_DIRECT' AND run_id IS NULL AND kind IN
        ('TEXT_GENERATION','IMAGE_GENERATION','VIDEO_GENERATION','AUDIO_GENERATION')));
ALTER TABLE canvas_item_media_input DROP CONSTRAINT ck_canvas_item_media_input_role;
ALTER TABLE canvas_item_media_input ADD CONSTRAINT ck_canvas_item_media_input_role
    CHECK (input_role IN ('REFERENCE','START_FRAME','END_FRAME','AUDIO_REFERENCE'));
ALTER TABLE media_default DROP CONSTRAINT media_default_kind_check;
ALTER TABLE media_default ADD CONSTRAINT media_default_kind_check
    CHECK (kind IN ('IMAGE_GENERATION','VIDEO_GENERATION','AUDIO_GENERATION'));
ALTER TABLE media_provider_connection DROP CONSTRAINT ck_media_connection_platform;
ALTER TABLE media_provider_connection ADD CONSTRAINT ck_media_connection_platform
    CHECK (platform IN ('LOCAL','MOCK','COMFYUI','OPENAI','GOOGLE','ARK','VOLCENGINE'));
ALTER TABLE call_log DROP CONSTRAINT call_log_kind_check;
ALTER TABLE call_log ADD CONSTRAINT call_log_kind_check CHECK (kind IN ('LLM','IMAGE','VIDEO','AUDIO'));
ALTER TABLE call_log DROP CONSTRAINT ck_call_log_operation_scope;
ALTER TABLE call_log ADD CONSTRAINT ck_call_log_operation_scope CHECK (
    (operation = 'CHAT' AND kind = 'LLM'
        AND ((run_id IS NOT NULL AND step_index IS NOT NULL AND step_index >= 0)
            OR (task_id IS NOT NULL AND run_id IS NULL AND step_index IS NULL)))
    OR (operation IN ('SUBMIT','POLL') AND kind IN ('IMAGE','VIDEO','AUDIO') AND task_id IS NOT NULL AND step_index IS NULL));
INSERT INTO media_capability (id,connection_id,name,enabled,version,current_version,created_at,updated_at)
VALUES ('00000000-0000-4000-8000-000000000104','00000000-0000-4000-8000-000000000101',
    'Mock audio',true,0,1,now(),now());
INSERT INTO media_capability_version (capability_id,version,adapter_id,mapping_sha256,spec_json,created_at)
VALUES ('00000000-0000-4000-8000-000000000104',1,'MOCK_AUDIO',
    'a99693d1b8d180ec693576125b0b513b22c88f0750d6ef9a70bfc01f8b2d47d5',
    '{"schemaVersion":1,"kind":"AUDIO_GENERATION"}',now());
INSERT INTO media_default (kind,capability_id,version)
VALUES ('AUDIO_GENERATION','00000000-0000-4000-8000-000000000104',0);
COMMENT ON TABLE canvas_item_media_input IS 'Ordered exact image/audio versions with card-local source and color identity.';
