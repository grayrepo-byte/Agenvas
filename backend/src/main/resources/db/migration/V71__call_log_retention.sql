CREATE TABLE audit_log_retention_settings (
    id smallint PRIMARY KEY CHECK (id = 1),
    retention_days integer CHECK (retention_days BETWEEN 1 AND 3650),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    purged_before timestamptz
);
INSERT INTO audit_log_retention_settings (id) VALUES (1);
CREATE INDEX ix_call_log_retention_time ON call_log(started_at, id);
COMMENT ON TABLE audit_log_retention_settings IS 'System-wide audit retention; null keeps forever. Monotonic purge watermark prevents deleted calls resurfacing through legacy ledger projections.';
