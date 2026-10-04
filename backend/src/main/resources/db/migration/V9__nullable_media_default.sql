-- A workflow can change its primary media type; the previous type must have no default
-- until the administrator explicitly selects a compatible capability again.
ALTER TABLE media_default ALTER COLUMN capability_id DROP NOT NULL;
COMMENT ON COLUMN media_default.capability_id IS '固定媒体能力身份；空值表示尚未选择匹配当前媒体类型的默认能力';
