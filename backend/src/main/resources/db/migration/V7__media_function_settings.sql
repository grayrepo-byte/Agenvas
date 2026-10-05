-- Operation routing is separate from ordinary generation defaults. No credentials or endpoints here.
INSERT INTO media_capability (id, connection_id, name, enabled, version, current_version, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000203', '00000000-0000-4000-8000-000000000201', '本地视频深度提取', true, 0, 1, now(), now()),
       ('00000000-0000-4000-8000-000000000204', '00000000-0000-4000-8000-000000000201', '本地视频音轨分离', true, 0, 1, now(), now());
INSERT INTO media_capability_version (capability_id, version, adapter_id, mapping_sha256, spec_json, created_at)
VALUES ('00000000-0000-4000-8000-000000000203', 1, 'LOCAL_VIDEO_PROCESSOR', encode(sha256(convert_to('local-video-depth-v1', 'UTF8')), 'hex'),
        '{"schemaVersion":1,"kind":"VIDEO_GENERATION","minimumSeconds":0,"maximumSeconds":30,"settings":{}}', now()),
       ('00000000-0000-4000-8000-000000000204', 1, 'LOCAL_VIDEO_AUDIO_EXTRACTOR', encode(sha256(convert_to('local-video-audio-v1', 'UTF8')), 'hex'),
        '{"schemaVersion":1,"kind":"AUDIO_GENERATION","settings":{}}', now());

CREATE TABLE media_function_setting (
    operation varchar(32) PRIMARY KEY CHECK (operation IN ('DEPTH_MAP', 'EXTRACT_AUDIO', 'UPSCALE')),
    capability_id uuid REFERENCES media_capability(id),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE media_function_setting IS 'Administrator-selected video processing capabilities, independent of generation defaults';
INSERT INTO media_function_setting (operation, capability_id)
VALUES ('DEPTH_MAP', '00000000-0000-4000-8000-000000000203'),
       ('EXTRACT_AUDIO', '00000000-0000-4000-8000-000000000204'), ('UPSCALE', NULL);
