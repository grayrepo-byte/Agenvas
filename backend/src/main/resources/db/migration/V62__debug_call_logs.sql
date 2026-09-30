CREATE TABLE audit_debug_settings (
    id smallint PRIMARY KEY CHECK (id = 1),
    debug_mode boolean NOT NULL DEFAULT false,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0)
);
INSERT INTO audit_debug_settings (id) VALUES (1);
CREATE TABLE call_log_debug (
    call_id uuid PRIMARY KEY REFERENCES call_log(id) ON DELETE CASCADE,
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    exchanges_json jsonb NOT NULL CHECK (jsonb_typeof(exchanges_json) = 'array')
);
COMMENT ON TABLE call_log_debug IS 'Opt-in HTTP bodies and URLs, with credentials and private reasoning removed; never part of project export.';
