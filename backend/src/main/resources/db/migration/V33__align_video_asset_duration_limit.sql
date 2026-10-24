-- Match the existing ten-minute source-video archive limit used by postproduction
-- and audio extraction; generation and final-composition limits remain separate.
ALTER TABLE asset DROP CONSTRAINT ck_asset_media_duration;
ALTER TABLE asset ADD CONSTRAINT ck_asset_media_duration CHECK (
    (media_kind = 'IMAGE' AND duration_ms IS NULL)
    OR (media_kind = 'VIDEO' AND (duration_ms IS NULL OR duration_ms BETWEEN 1 AND 600000))
    OR (media_kind = 'AUDIO' AND duration_ms IS NOT NULL AND duration_ms BETWEEN 1 AND 600000
        AND width IS NULL AND height IS NULL)
);
COMMENT ON CONSTRAINT ck_asset_media_duration ON asset IS
    '媒体时长约束：图片无时长，视频与音频最长十分钟；保留旧视频未知时长，音频必须有时长且无画面尺寸。';
