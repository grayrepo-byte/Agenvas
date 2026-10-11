ALTER TABLE media_draft DROP CONSTRAINT ck_media_draft_duration;
ALTER TABLE media_draft ADD CONSTRAINT ck_media_draft_duration
    CHECK (duration_seconds IS NULL OR duration_seconds BETWEEN 1 AND 60);
COMMENT ON CONSTRAINT ck_media_draft_duration ON media_draft
    IS '草稿允许保存 1–60 个整数秒，以保留切换能力前的时长；运行范围由所选能力另行校验。';
