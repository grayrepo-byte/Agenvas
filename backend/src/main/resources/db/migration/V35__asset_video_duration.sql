ALTER TABLE asset
    ADD COLUMN duration_ms integer;

ALTER TABLE asset
    ADD CONSTRAINT ck_asset_video_duration CHECK (
        (media_kind = 'IMAGE' AND duration_ms IS NULL)
        OR (media_kind = 'VIDEO' AND (duration_ms IS NULL
            OR duration_ms BETWEEN 1 AND 60000))
    );

COMMENT ON COLUMN asset.duration_ms IS
    'Verified MP4 duration at archive time; null only for images or pre-V35 video assets.';
