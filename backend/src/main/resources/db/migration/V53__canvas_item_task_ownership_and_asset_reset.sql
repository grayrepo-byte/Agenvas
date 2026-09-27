-- Direct media execution belongs to one CanvasItem even when several cards share an Artifact.
-- V52 removed every pre-existing task, so the new nullable owner has no legacy rows to infer.
ALTER TABLE task_artifact_target
    ADD COLUMN canvas_item_id uuid,
    ADD CONSTRAINT fk_task_artifact_target_canvas_item
        FOREIGN KEY (project_id, canvas_item_id)
        REFERENCES canvas_item(project_id, id);

CREATE INDEX ix_task_artifact_target_canvas_item
    ON task_artifact_target(project_id, canvas_item_id)
    WHERE canvas_item_id IS NOT NULL;

-- Flyway cannot delete files from the private asset volume. A startup runner locks this marker,
-- removes UUID-named project directories without following links, then records completion.
CREATE TABLE creative_data_reset_marker (
    id smallint PRIMARY KEY CHECK (id = 1),
    completed_at timestamptz
);

INSERT INTO creative_data_reset_marker (id) VALUES (1);
