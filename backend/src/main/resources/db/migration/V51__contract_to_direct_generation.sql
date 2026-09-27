-- The canvas contracts to TEXT / IMAGE / VIDEO. Character, scene and shot artifacts, the whole
-- three-shot planning pipeline (execution plans, approvals, keyframe selection), the media export
-- (ordered silent stitching) and the shot-scoped redo go away. Direct generation tasks and the
-- project export manifest stay.
--
-- artifact_version rows are guarded by an immutability trigger, so the trigger is suspended while
-- the removed kinds are purged and restored afterwards.
DROP TRIGGER artifact_version_no_delete ON artifact_version;

-- Removed pipeline tables. The task-side foreign key is released first so execution_plan can go.
-- plan_step and plan_approval are dropped explicitly: CASCADE on execution_plan would release the
-- foreign keys that point at it but leave the referencing tables themselves in place.
ALTER TABLE task DROP CONSTRAINT fk_task_execution_plan;
DROP TABLE shot_keyframe_selection;
DROP TABLE export_proposal;
DROP TABLE plan_approval;
DROP TABLE plan_step;
DROP TABLE execution_plan;
DROP TABLE shot_duration_upgrade;
-- The semantic artifact-to-artifact relation lost its only writer with link_artifacts.
DROP TABLE artifact_relation;

-- Tasks that belong to the removed pipeline. Plan steps are matched on plan_id, which is still
-- present here: they targeted a new media output slot, so their artifact target is NULL and the
-- removed-kind join below cannot see them. Media exports and tasks pinned to a removed artifact
-- kind are matched as well. Their targets, attempts, audit rows and dependency edges go with them.
CREATE TEMP TABLE removed_task ON COMMIT DROP AS
SELECT t.project_id, t.id
FROM task t
WHERE t.plan_id IS NOT NULL
   OR t.kind = 'MEDIA_EXPORT'
   OR t.origin = 'PROJECT_EXPORT'
   OR EXISTS (
       SELECT 1
       FROM task_artifact_target tat
       JOIN artifact a ON a.project_id = tat.project_id AND a.id = tat.artifact_id
       WHERE tat.task_id = t.id AND a.kind IN ('CHARACTER', 'SCENE', 'SHOT'));

-- Tasks keep their run scope only. plan_id and the two partial step-attempt indexes it carried are
-- replaced by a single run-scoped unique index once the pipeline tasks are gone.
DROP INDEX uq_task_planned_step_attempt;
DROP INDEX uq_task_unplanned_step_attempt;
DROP INDEX uq_task_project_export_key;
ALTER TABLE task DROP COLUMN plan_id;

DELETE FROM task_dependency
WHERE (project_id, task_id) IN (SELECT project_id, id FROM removed_task)
   OR (project_id, depends_on_task_id) IN (SELECT project_id, id FROM removed_task);
DELETE FROM call_log WHERE task_id IN (SELECT id FROM removed_task);
DELETE FROM usage_ledger WHERE task_id IN (SELECT id FROM removed_task);
DELETE FROM provider_attempt WHERE (project_id, task_id) IN (SELECT project_id, id FROM removed_task);
DELETE FROM task_late_result WHERE (project_id, task_id) IN (SELECT project_id, id FROM removed_task);
DELETE FROM task_manual_replacement
WHERE (project_id, original_task_id) IN (SELECT project_id, id FROM removed_task)
   OR (project_id, replacement_task_id) IN (SELECT project_id, id FROM removed_task);
DELETE FROM task_artifact_target WHERE (project_id, task_id) IN (SELECT project_id, id FROM removed_task);
DELETE FROM task WHERE (project_id, id) IN (SELECT project_id, id FROM removed_task);

CREATE UNIQUE INDEX uq_task_run_step_attempt ON task (run_id, step_key, attempt_no);

-- Removed artifacts and everything anchored to them.
CREATE TEMP TABLE removed_artifact ON COMMIT DROP AS
SELECT id, project_id FROM artifact WHERE kind IN ('CHARACTER', 'SCENE', 'SHOT');

CREATE TEMP TABLE removed_version ON COMMIT DROP AS
SELECT v.id, v.project_id FROM artifact_version v
JOIN removed_artifact a ON a.id = v.artifact_id;

-- artifact.current_version_id -> artifact_version must release before the versions are purged.
UPDATE artifact SET current_version_id = NULL WHERE id IN (SELECT id FROM removed_artifact);

-- artifact_version_reference has no ON DELETE CASCADE on its target side, so both ends are cleared
-- explicitly; media drafts and agent bindings outside the removed kinds may still point at them.
DELETE FROM artifact_version_reference
WHERE source_version_id IN (SELECT id FROM removed_version)
   OR target_version_id IN (SELECT id FROM removed_version);

UPDATE media_draft SET input_image_version_id = NULL
WHERE input_image_version_id IN (SELECT id FROM removed_version);

DELETE FROM agent_binding
WHERE (project_id, artifact_id) IN (SELECT project_id, id FROM removed_artifact);

DELETE FROM canvas_item
WHERE (project_id, artifact_id) IN (SELECT project_id, id FROM removed_artifact);

DELETE FROM artifact_version WHERE id IN (SELECT id FROM removed_version);
DELETE FROM artifact WHERE id IN (SELECT id FROM removed_artifact);

CREATE TRIGGER artifact_version_no_delete
    BEFORE DELETE ON artifact_version
    FOR EACH ROW EXECUTE FUNCTION reject_artifact_version_mutation();

-- Contracts only known types now.
ALTER TABLE artifact DROP CONSTRAINT ck_artifact_kind;
ALTER TABLE artifact ADD CONSTRAINT ck_artifact_kind
    CHECK (kind IN ('TEXT', 'IMAGE', 'VIDEO'));

ALTER TABLE task DROP CONSTRAINT ck_task_kind;
ALTER TABLE task ADD CONSTRAINT ck_task_kind CHECK (kind IN (
    'AGENT_TURN', 'TEXT_GENERATION', 'IMAGE_GENERATION', 'VIDEO_GENERATION', 'ASSET_INGEST'
));

-- Dropping plan_id already removed ck_task_origin_scope, which referenced it.
ALTER TABLE task ADD CONSTRAINT ck_task_origin_scope CHECK (
    (origin = 'AGENT' AND run_id IS NOT NULL)
    OR (origin = 'USER_DIRECT' AND run_id IS NULL
        AND kind IN ('TEXT_GENERATION', 'IMAGE_GENERATION', 'VIDEO_GENERATION'))
);

-- A run waiting on a plan approval has nothing left to approve: its plan and every step it could
-- have produced are gone above. Release the project's active-run slot, end the run, and end its
-- non-terminal tasks so no worker waits on a removed pipeline.
CREATE TEMP TABLE orphaned_run ON COMMIT DROP AS
SELECT id, project_id FROM agent_run WHERE status = 'WAITING_APPROVAL';

UPDATE project SET active_run_id = NULL
WHERE active_run_id IN (SELECT id FROM orphaned_run);

UPDATE task SET cancel_requested = true,
    status = CASE WHEN status IN ('PENDING', 'READY') THEN 'CANCELED' ELSE status END,
    completed_at = CASE WHEN status IN ('PENDING', 'READY') THEN now() ELSE completed_at END,
    updated_at = now(),
    version = version + 1
WHERE run_id IN (SELECT id FROM orphaned_run)
  AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED');

UPDATE agent_run SET status = 'CANCELED', completed_at = now(), updated_at = now()
WHERE id IN (SELECT id FROM orphaned_run);

ALTER TABLE agent_run DROP CONSTRAINT ck_agent_run_status;
ALTER TABLE agent_run ADD CONSTRAINT ck_agent_run_status CHECK (status IN (
    'QUEUED', 'RUNNING', 'WAITING_TASKS', 'BLOCKED',
    'CANCEL_REQUESTED', 'CANCELED', 'FAILED', 'SUCCEEDED'
));

-- Tool results recorded while a proposal waited for approval keep the removed status value; the
-- projection reads it with a strict enum parse, so the recorded outcome is normalized to the only
-- remaining value. Rejections are never persisted, so no other value can occur.
UPDATE tool_execution
SET result_json = jsonb_set(result_json, '{status}', '"SUCCEEDED"')
WHERE result_json ->> 'status' = 'WAITING_APPROVAL';
