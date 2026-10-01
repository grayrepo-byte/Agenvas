-- Cleanup now deletes complete terminal execution ledgers together with call logs.
-- There is no hidden legacy history, so a projection watermark is unnecessary.
ALTER TABLE audit_log_retention_settings DROP COLUMN purged_before;
DROP INDEX ix_call_log_retention_time;
COMMENT ON TABLE audit_log_retention_settings IS
    'System-wide retention for terminal execution history: calls, debug bodies, model rounds, tools and provider attempts. Null keeps forever; business identities/results remain.';
