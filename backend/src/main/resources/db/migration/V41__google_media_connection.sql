ALTER TABLE media_provider_connection
    DROP CONSTRAINT ck_media_connection_platform;

ALTER TABLE media_provider_connection
    ADD CONSTRAINT ck_media_connection_platform CHECK
        (platform IN ('MOCK', 'COMFYUI', 'OPENAI', 'ARK', 'GOOGLE'));
