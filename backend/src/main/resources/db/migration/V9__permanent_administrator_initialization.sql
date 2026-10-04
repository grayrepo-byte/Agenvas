-- Completion belongs to the installation, not the current account's active status.
ALTER TABLE installation_lock ADD COLUMN initialized_at timestamptz;
COMMENT ON COLUMN installation_lock.initialized_at IS
    '首次管理员初始化的完成时间；停用或删除管理员不会重新开放初始化';

-- Preserve initialized installations, including deployments with a disabled administrator.
UPDATE installation_lock
SET initialized_at = (SELECT min(created_at) FROM app_user)
WHERE initialized_at IS NULL AND EXISTS (SELECT 1 FROM app_user);
