ALTER TABLE media_capability ADD COLUMN deleted_at timestamptz;
COMMENT ON COLUMN media_capability.deleted_at IS '能力删除时间；目录不再展示，历史任务保留不可变版本用于恢复';
