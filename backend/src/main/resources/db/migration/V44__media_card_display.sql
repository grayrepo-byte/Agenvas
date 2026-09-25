-- The card's visible face is independent of its saved editable draft and
-- independently selected immutable result.
ALTER TABLE media_draft ADD COLUMN display_mode varchar(12) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE media_draft ADD CONSTRAINT ck_media_draft_display_mode
    CHECK (display_mode IN ('DRAFT', 'RESULT'));

INSERT INTO media_draft (project_id, artifact_id, prompt, version, created_at,
    updated_at, display_mode)
SELECT a.project_id, a.id, coalesce(v.content_json ->> 'prompt', ''), 0,
       a.created_at, a.updated_at, 'RESULT'
FROM artifact a
LEFT JOIN artifact_version v ON v.id = a.current_version_id
WHERE a.kind IN ('IMAGE', 'VIDEO')
ON CONFLICT (project_id, artifact_id) DO NOTHING;
