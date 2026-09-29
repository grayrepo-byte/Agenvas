ALTER TABLE media_provider_connection
    DROP CONSTRAINT ck_media_connection_platform;

ALTER TABLE media_provider_connection
    ADD CONSTRAINT ck_media_connection_platform CHECK
        (platform IN ('LOCAL', 'MOCK', 'COMFYUI', 'OPENAI', 'ARK', 'GOOGLE'));

INSERT INTO media_provider_connection
    (id, name, platform, enabled, version, current_version, created_at, updated_at)
VALUES
    ('00000000-0000-4000-8000-000000000201', '本地图片处理', 'LOCAL', true, 0, 1, now(), now());

INSERT INTO media_provider_connection_version
    (connection_id, version, origin, origin_sha256, created_at)
VALUES
    ('00000000-0000-4000-8000-000000000201', 1, NULL, NULL, now());

INSERT INTO media_capability
    (id, connection_id, name, enabled, version, current_version, created_at, updated_at)
VALUES
    ('00000000-0000-4000-8000-000000000202', '00000000-0000-4000-8000-000000000201',
        '本地图片处理', true, 0, 1, now(), now());

INSERT INTO media_capability_version
    (capability_id, version, adapter_id, mapping_sha256, spec_json, created_at)
VALUES
    ('00000000-0000-4000-8000-000000000202', 1, 'LOCAL_IMAGE_PROCESSOR',
        '4a733fddf137aaef35e919f050a6f5f4c5968fb9444c6689b775c3e365e25be9',
        '{"schemaVersion":1,"kind":"IMAGE_GENERATION","maxReferenceImages":1,"settings":{}}', now());
