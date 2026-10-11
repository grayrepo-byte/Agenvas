ALTER TABLE media_provider_connection DROP CONSTRAINT ck_media_connection_platform;
ALTER TABLE media_provider_connection ADD CONSTRAINT ck_media_connection_platform
    CHECK (platform IN ('LOCAL', 'MOCK', 'COMFYUI', 'OPENAI', 'GOOGLE', 'ARK', 'VOLCENGINE', 'AUTODL', 'RUNNINGHUB', 'MINIMAX', 'KLING', 'WAN'));
COMMENT ON CONSTRAINT ck_media_connection_platform ON media_provider_connection
    IS '媒体连接平台白名单，含可灵与百炼万相；既有连接版本与加密凭据保留。';
