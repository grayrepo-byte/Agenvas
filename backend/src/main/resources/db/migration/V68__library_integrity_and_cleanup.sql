-- Explicit NOT NULL predicates prevent SQL UNKNOWN from accepting incomplete snapshots.
ALTER TABLE library_file ADD CONSTRAINT library_file_identity CHECK (
    metadata_json ?& ARRAY['schemaVersion','id','ownerId','kind','objectKey','contentType','byteSize','sha256']
    AND metadata_json->>'id' = id::text AND metadata_json->>'ownerId' = owner_id::text
    AND metadata_json->>'kind' = kind AND (metadata_json->>'byteSize')::bigint > 0
    AND metadata_json->>'sha256' ~ '^[0-9a-f]{64}$');
ALTER TABLE library_file ADD UNIQUE (owner_id, id, kind);
ALTER TABLE library_entry ADD FOREIGN KEY (owner_id, file_id, kind) REFERENCES library_file(owner_id, id, kind);
ALTER TABLE library_entry ADD CONSTRAINT library_entry_complete CHECK (
    kind <> 'TEXT' OR (text_content IS NOT NULL AND text_content ?& ARRAY['format','text']
        AND jsonb_typeof(text_content->'text') = 'string'));
CREATE TABLE library_cleanup (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES app_user(id),
    metadata_json jsonb NOT NULL CHECK (jsonb_typeof(metadata_json) = 'object'),
    created_at timestamptz NOT NULL
);
COMMENT ON TABLE library_cleanup IS 'Durable idempotent file removal after catalogue deletion or duplicate save';
