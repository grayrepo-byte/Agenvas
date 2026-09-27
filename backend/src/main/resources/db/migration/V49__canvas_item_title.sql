-- A card title belongs to its CanvasItem presentation, not to the referenced
-- Artifact or AgentInstance. Existing cards keep the name users already saw.
ALTER TABLE canvas_item ADD COLUMN title varchar(160);

UPDATE canvas_item ci
SET title = a.title
FROM artifact a
WHERE ci.subject_type = 'ARTIFACT'
  AND ci.project_id = a.project_id
  AND ci.subject_id = a.id;

UPDATE canvas_item ci
SET title = ai.name
FROM agent_instance ai
WHERE ci.subject_type = 'AGENT'
  AND ci.project_id = ai.project_id
  AND ci.subject_id = ai.id;

ALTER TABLE canvas_item
    ALTER COLUMN title SET NOT NULL,
    ADD CONSTRAINT ck_canvas_item_title
        CHECK (length(btrim(title)) BETWEEN 1 AND 160);

COMMENT ON COLUMN canvas_item.title IS
    'Per-card display title initialized from its subject and edited independently afterward.';
COMMENT ON TABLE canvas_item IS
    'Per-card title and spatial presentation; referenced business content remains outside this row.';
