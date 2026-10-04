-- Keep existing video selections and CAS versions while separating image/video tool identities.
ALTER TABLE media_function_setting DROP CONSTRAINT media_function_setting_operation_check;
UPDATE media_function_setting SET operation = 'VIDEO_' || operation;
ALTER TABLE media_function_setting ADD CONSTRAINT media_function_setting_operation_check
    CHECK (operation IN ('IMAGE_SMART_EDIT', 'IMAGE_RELIGHT', 'IMAGE_OUTPAINT', 'IMAGE_THREE_VIEW', 'IMAGE_LAYER_SPLIT', 'IMAGE_EXPRESSION_EDIT', 'IMAGE_REMOVE_BACKGROUND', 'IMAGE_OBJECT_REMOVE', 'IMAGE_VIEW_ANGLE', 'IMAGE_DEPTH_MAP', 'IMAGE_UPSCALE', 'IMAGE_CROP', 'IMAGE_ROTATE', 'IMAGE_FLIP_HORIZONTAL', 'IMAGE_FLIP_VERTICAL', 'VIDEO_DEPTH_MAP', 'VIDEO_EXTRACT_AUDIO', 'VIDEO_UPSCALE'));
COMMENT ON TABLE media_function_setting IS 'Administrator-selected image/video processing capabilities, independent of generation defaults';
INSERT INTO media_function_setting (operation, capability_id)
VALUES ('IMAGE_SMART_EDIT', NULL),
       ('IMAGE_RELIGHT', NULL),
       ('IMAGE_OUTPAINT', NULL),
       ('IMAGE_THREE_VIEW', NULL),
       ('IMAGE_LAYER_SPLIT', NULL),
       ('IMAGE_EXPRESSION_EDIT', NULL),
       ('IMAGE_REMOVE_BACKGROUND', NULL),
       ('IMAGE_OBJECT_REMOVE', NULL),
       ('IMAGE_VIEW_ANGLE', NULL),
       ('IMAGE_DEPTH_MAP', '00000000-0000-4000-8000-000000000202'),
       ('IMAGE_UPSCALE', '00000000-0000-4000-8000-000000000202'),
       ('IMAGE_CROP', '00000000-0000-4000-8000-000000000202'),
       ('IMAGE_ROTATE', '00000000-0000-4000-8000-000000000202'),
       ('IMAGE_FLIP_HORIZONTAL', '00000000-0000-4000-8000-000000000202'),
       ('IMAGE_FLIP_VERTICAL', '00000000-0000-4000-8000-000000000202');
-- Preserve a compatible existing generation default once; subsequent defaults do not change tool routes.
UPDATE media_function_setting f SET capability_id = c.id
FROM media_default d
JOIN media_capability c ON c.id = d.capability_id AND c.enabled
JOIN media_provider_connection connection ON connection.id = c.connection_id AND connection.enabled
JOIN media_capability_version v ON v.capability_id = c.id AND v.version = c.current_version
WHERE d.kind = 'IMAGE_GENERATION' AND v.adapter_id IN ('OPENAI_GPT_IMAGE_2', 'GOOGLE_NANO_BANANA_2', 'COMFY_IMAGE_V1')
  AND COALESCE((v.spec_json->>'maxReferenceImages')::int, 0) > 0
  AND (f.operation NOT IN ('IMAGE_REMOVE_BACKGROUND', 'IMAGE_LAYER_SPLIT') OR v.adapter_id = 'OPENAI_GPT_IMAGE_2')
  AND f.operation IN ('IMAGE_SMART_EDIT', 'IMAGE_RELIGHT', 'IMAGE_OUTPAINT', 'IMAGE_THREE_VIEW', 'IMAGE_LAYER_SPLIT', 'IMAGE_EXPRESSION_EDIT', 'IMAGE_REMOVE_BACKGROUND', 'IMAGE_OBJECT_REMOVE', 'IMAGE_VIEW_ANGLE');

COMMENT ON COLUMN media_function_setting.operation IS 'Qualified IMAGE_ or VIDEO_ tool identity';
COMMENT ON COLUMN media_function_setting.capability_id IS 'Selected published capability; null disables new execution';
COMMENT ON COLUMN media_function_setting.version IS 'CAS version changed only by explicit function setting updates';
COMMENT ON COLUMN media_function_setting.updated_at IS 'Last function setting update time in UTC';
COMMENT ON CONSTRAINT media_function_setting_operation_check ON media_function_setting IS 'Allow only compiled image and video processing functions';
COMMENT ON CONSTRAINT media_function_setting_capability_id_fkey ON media_function_setting IS 'Function routes refer to existing media capabilities';
COMMENT ON CONSTRAINT media_function_setting_version_check ON media_function_setting IS 'Function CAS version must be nonnegative';
COMMENT ON CONSTRAINT media_function_setting_pkey ON media_function_setting IS 'One independent routing setting per qualified tool';
COMMENT ON INDEX media_function_setting_pkey IS 'Unique lookup for qualified processing functions';
