ALTER TABLE app_user
    ADD COLUMN password_changed_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN app_user.password_changed_at IS
    'Time of the latest password change; used for session and audit decisions.';
COMMENT ON COLUMN app_user.version IS
    'Optimistic version for security-sensitive administrator updates.';
