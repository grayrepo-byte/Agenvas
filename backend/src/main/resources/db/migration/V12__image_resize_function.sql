-- Proportional pixel resizing is a deterministic local tool, independent of AI upscale.
ALTER TABLE media_function_setting DROP CONSTRAINT media_function_setting_operation_check;
ALTER TABLE media_function_setting ADD CONSTRAINT media_function_setting_operation_check
    CHECK (operation IN ('IMAGE_SMART_EDIT', 'IMAGE_RELIGHT', 'IMAGE_OUTPAINT', 'IMAGE_THREE_VIEW', 'IMAGE_LAYER_SPLIT', 'IMAGE_EXPRESSION_EDIT', 'IMAGE_REMOVE_BACKGROUND', 'IMAGE_OBJECT_REMOVE', 'IMAGE_VIEW_ANGLE', 'IMAGE_DEPTH_MAP', 'IMAGE_UPSCALE', 'IMAGE_RESIZE', 'IMAGE_CROP', 'IMAGE_ROTATE', 'IMAGE_FLIP_HORIZONTAL', 'IMAGE_FLIP_VERTICAL', 'VIDEO_DEPTH_MAP', 'VIDEO_EXTRACT_AUDIO', 'VIDEO_UPSCALE'));
COMMENT ON CONSTRAINT media_function_setting_operation_check ON media_function_setting IS 'Allow only compiled image and video processing functions';
INSERT INTO media_function_setting (operation, capability_id)
VALUES ('IMAGE_RESIZE', '00000000-0000-4000-8000-000000000202');
