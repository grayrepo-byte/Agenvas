-- Composition is a local processing capability, never an ordinary generation default.
INSERT INTO media_capability (id, connection_id, name, enabled, version, current_version, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000205', '00000000-0000-4000-8000-000000000201',
        '本地智能配音合成', true, 0, 1, now(), now());
INSERT INTO media_capability_version (capability_id, version, adapter_id, mapping_sha256, spec_json, created_at)
VALUES ('00000000-0000-4000-8000-000000000205', 1, 'LOCAL_VIDEO_COMPOSITOR',
        encode(sha256(convert_to('local-video-composition-v1', 'UTF8')), 'hex'),
        '{"schemaVersion":1,"kind":"VIDEO_GENERATION","minimumSeconds":1,"maximumSeconds":60,"settings":{}}', now());
