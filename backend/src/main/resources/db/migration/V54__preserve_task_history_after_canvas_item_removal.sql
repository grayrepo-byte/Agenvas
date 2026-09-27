-- Task history survives deleting its originating card; only the optional live-card link is cleared.
ALTER TABLE task_artifact_target
    DROP CONSTRAINT fk_task_artifact_target_canvas_item;

ALTER TABLE task_artifact_target
    ADD CONSTRAINT fk_task_artifact_target_canvas_item
        FOREIGN KEY (project_id, canvas_item_id)
        REFERENCES canvas_item(project_id, id)
        ON DELETE SET NULL (canvas_item_id);
