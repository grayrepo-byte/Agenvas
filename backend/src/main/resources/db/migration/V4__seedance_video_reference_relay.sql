ALTER TABLE storage_settings ADD COLUMN relay_profile_id uuid REFERENCES storage_profile(id);
COMMENT ON COLUMN storage_settings.relay_profile_id IS 'Optional media relay destination; independent of the archive default';

CREATE TABLE media_relay_object (
    id uuid PRIMARY KEY,
    profile_id uuid NOT NULL REFERENCES storage_profile(id),
    object_key text NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT media_relay_object_location_unique UNIQUE (profile_id, object_key),
    CONSTRAINT media_relay_object_retention_check CHECK (expires_at > created_at)
);
COMMENT ON TABLE media_relay_object IS 'Temporary provider input copies registered before upload, retained for durable cleanup';
COMMENT ON COLUMN media_relay_object.id IS 'Server-generated copy identity';
COMMENT ON COLUMN media_relay_object.profile_id IS 'Pinned object storage connection for this temporary copy';
COMMENT ON COLUMN media_relay_object.object_key IS 'Private object key; never a URL or signature';
COMMENT ON COLUMN media_relay_object.expires_at IS 'Earliest deletion time, later than provider input URL expiration';
COMMENT ON COLUMN media_relay_object.created_at IS 'Copy registration time before network upload';
CREATE INDEX media_relay_object_expiry_idx ON media_relay_object(expires_at);

COMMENT ON CONSTRAINT storage_settings_relay_profile_id_fkey ON storage_settings IS 'Relay selection must refer to a retained object storage connection';
COMMENT ON CONSTRAINT media_relay_object_pkey ON media_relay_object IS 'Unique temporary copy identity';
COMMENT ON CONSTRAINT media_relay_object_profile_id_fkey ON media_relay_object IS 'Retain the destination until every relay copy has been cleaned up';
COMMENT ON CONSTRAINT media_relay_object_location_unique ON media_relay_object IS 'One cleanup record per temporary object';
COMMENT ON CONSTRAINT media_relay_object_retention_check ON media_relay_object IS 'Retention must end after registration';
COMMENT ON INDEX media_relay_object_pkey IS 'Temporary copy identity lookup';
COMMENT ON INDEX media_relay_object_location_unique IS 'Unique temporary object location';
COMMENT ON INDEX media_relay_object_expiry_idx IS 'Bounded expired-copy cleanup scan';
