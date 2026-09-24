ALTER TABLE asset
    ADD COLUMN thumbnail_key varchar(200),
    ADD COLUMN thumbnail_byte_size bigint,
    ADD COLUMN thumbnail_sha256 char(64);

ALTER TABLE asset
    ADD CONSTRAINT uq_asset_thumbnail_key UNIQUE (thumbnail_key),
    ADD CONSTRAINT ck_asset_thumbnail_complete CHECK (
        (thumbnail_key IS NULL AND thumbnail_byte_size IS NULL AND thumbnail_sha256 IS NULL)
        OR (thumbnail_key IS NOT NULL AND thumbnail_byte_size > 0
            AND thumbnail_sha256 ~ '^[0-9a-f]{64}$')
    );

COMMENT ON COLUMN asset.thumbnail_key IS
    'Private, bounded PNG preview created before a new image Asset becomes READY.';
