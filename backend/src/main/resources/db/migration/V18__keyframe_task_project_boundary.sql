ALTER TABLE shot_keyframe_selection
    ADD CONSTRAINT fk_keyframe_task_project
    FOREIGN KEY (project_id, source_task_id)
    REFERENCES task (project_id, id);

COMMENT ON CONSTRAINT fk_keyframe_task_project ON shot_keyframe_selection IS
    'Selected keyframe source Task must belong to the same project as the shot and image.';
