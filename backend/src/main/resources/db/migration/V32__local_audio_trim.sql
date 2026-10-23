-- Local audio editing has a dedicated capability and never replaces a generation default.
INSERT INTO media_capability (id, connection_id, name, enabled, version, current_version, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000206', '00000000-0000-4000-8000-000000000201',
        '本地音频裁剪', true, 0, 1, now(), now());
INSERT INTO media_capability_version (capability_id, version, adapter_id, mapping_sha256, spec_json, created_at)
VALUES ('00000000-0000-4000-8000-000000000206', 1, 'LOCAL_AUDIO_TRIMMER',
        encode(sha256(convert_to('local-audio-trim-v1', 'UTF8')), 'hex'),
        '{"schemaVersion":1,"kind":"AUDIO_GENERATION","minimumSeconds":0,"maximumSeconds":0,"settings":{}}', now());
