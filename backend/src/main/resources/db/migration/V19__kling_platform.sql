ALTER TABLE media_provider_connection DROP CONSTRAINT ck_media_connection_platform;
ALTER TABLE media_provider_connection ADD CONSTRAINT ck_media_connection_platform
    CHECK (platform IN ('LOCAL', 'MOCK', 'COMFYUI', 'OPENAI', 'GOOGLE', 'ARK', 'VOLCENGINE', 'AUTODL', 'RUNNINGHUB', 'MINIMAX', 'KLING'));
COMMENT ON CONSTRAINT ck_media_connection_platform ON media_provider_connection
    IS '媒体连接平台白名单，包含可灵官方图片与视频 API；不改变既有连接与固定任务版本。';
