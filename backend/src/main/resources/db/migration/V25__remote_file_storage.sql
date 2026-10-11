ALTER TABLE storage_profile DROP CONSTRAINT storage_profile_provider_check;
ALTER TABLE storage_profile ADD CONSTRAINT storage_profile_provider_check
    CHECK (provider IN ('ALIYUN_OSS', 'TENCENT_COS', 'S3', 'SMB', 'WEBDAV'));
COMMENT ON CONSTRAINT storage_profile_provider_check ON storage_profile IS 'Supported object and remote file storage protocols';
COMMENT ON TABLE storage_profile IS 'Pinned archive destinations and encrypted object-store or remote-file credentials';
COMMENT ON COLUMN storage_profile.endpoint IS 'Object service origin, HTTPS WebDAV collection, or smb://host/share/base directory; no embedded credentials';
COMMENT ON COLUMN storage_profile.region IS 'Object-store region; empty for SMB and WebDAV';
COMMENT ON COLUMN storage_profile.bucket IS 'Object-store bucket; empty for SMB and WebDAV';
COMMENT ON COLUMN storage_profile.access_key_mask IS 'Masked access key ID or remote-file username; never a password';
