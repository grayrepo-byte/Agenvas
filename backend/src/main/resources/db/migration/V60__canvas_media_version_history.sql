-- A media node owns its result history; editing creates another node with its own history.
ALTER TABLE canvas_item ADD COLUMN media_selection_epoch bigint NOT NULL DEFAULT 0;
ALTER TABLE canvas_item ADD CONSTRAINT ck_canvas_media_selection_epoch
    CHECK (media_selection_epoch >= 0);

CREATE TABLE canvas_item_media_version (
    project_id uuid NOT NULL,
    canvas_item_id uuid NOT NULL,
    artifact_version_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (canvas_item_id, artifact_version_id),
    FOREIGN KEY (project_id, canvas_item_id)
        REFERENCES canvas_item (project_id, id) ON DELETE CASCADE,
    FOREIGN KEY (project_id, artifact_version_id)
        REFERENCES artifact_version (project_id, id)
);

-- Preserve existing cards exactly as they are. Do not merge old derived nodes.
INSERT INTO canvas_item_media_version
    (project_id, canvas_item_id, artifact_version_id, created_at)
SELECT ci.project_id, ci.id, ci.selected_version_id, ci.created_at
FROM canvas_item ci WHERE ci.selected_version_id IS NOT NULL
-- Older pre-created output nodes temporarily held the source parent for result CAS.
-- That provisional parent is not a result owned by the new node.
AND NOT EXISTS (
    SELECT 1 FROM task t
    WHERE t.project_id = ci.project_id AND t.input_json->>'canvasItemId' = ci.id::text
      AND t.input_json->>'sourceCanvasItemId' <> ci.id::text
      AND t.input_json->>'parentVersionId' = ci.selected_version_id::text
);

-- Recover archived task outputs, including unselected or canceled late results.
INSERT INTO canvas_item_media_version
    (project_id, canvas_item_id, artifact_version_id, created_at)
SELECT ci.project_id, ci.id, av.id, av.created_at
FROM canvas_item ci
JOIN artifact_version av ON av.project_id = ci.project_id AND av.artifact_id = ci.artifact_id
JOIN task t ON t.id::text = av.content_json->>'sourceTaskId'
    AND t.project_id = ci.project_id AND t.input_json->>'canvasItemId' = ci.id::text
ON CONFLICT DO NOTHING;

COMMENT ON TABLE canvas_item_media_version IS
    'Card-local immutable result history; switching a card never updates pinned inputs or library defaults.';
COMMENT ON COLUMN canvas_item.media_selection_epoch IS
    'Content selection revision, independent of layout CAS; prevents late tasks from replacing user selections.';
