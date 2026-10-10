ALTER TABLE media_provider_connection DROP CONSTRAINT ck_media_connection_platform;
ALTER TABLE media_provider_connection ADD CONSTRAINT ck_media_connection_platform
    CHECK (platform IN ('LOCAL', 'MOCK', 'COMFYUI', 'OPENAI', 'GOOGLE', 'ARK', 'VOLCENGINE', 'AUTODL', 'RUNNINGHUB', 'MINIMAX'));
COMMENT ON CONSTRAINT ck_media_connection_platform ON media_provider_connection
    IS '媒体连接平台白名单，包含 MiniMax 官方 H3 视频 API；不改变既有连接与固定任务版本。';
