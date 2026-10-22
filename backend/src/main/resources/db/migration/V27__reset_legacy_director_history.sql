-- The built-in project director replaces all pre-upgrade assistant identities.
-- This explicitly authorized, one-time reset removes conversations and execution
-- bodies, while retaining immutable creative results, direct tasks and billing.
-- Flyway executes the migration transactionally. Block concurrent application
-- writes before checking safety, so no task can be accepted between check/delete.
LOCK TABLE project, agent_instance, agent_conversation, agent_run, task,
    agent_media_approval, agent_skill_binding, skill_binding_command, llm_turn,
    tool_execution, provider_attempt, task_artifact_target, task_late_result,
    task_manual_replacement, task_provider_poll_retry, call_log, idempotency_record,
    project_event IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM agent_run WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED'))
       OR EXISTS (SELECT 1 FROM task WHERE run_id IS NOT NULL
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED')) THEN
        RAISE EXCEPTION 'Director history reset requires all Agent runs and tasks to be terminal; resolve active or UNKNOWN work before upgrading';
    END IF;
END;
$$;

CREATE TEMP TABLE director_reset_project ON COMMIT DROP AS
SELECT DISTINCT project_id FROM agent_instance;

CREATE TEMP TABLE director_reset_task ON COMMIT DROP AS
SELECT id FROM task WHERE run_id IS NOT NULL;

CREATE TEMP TABLE director_reset_identity ON COMMIT DROP AS
SELECT id FROM agent_instance
UNION SELECT id FROM agent_conversation
UNION SELECT id FROM agent_run
UNION SELECT id FROM director_reset_task
UNION SELECT id FROM agent_media_approval
-- Retention cleanup can already have removed a Run, while its audit identifier
-- survives. Clear those historical receipts and bodies as part of the same reset.
UNION SELECT run_id FROM call_log WHERE run_id IS NOT NULL
UNION SELECT task_id FROM call_log WHERE run_id IS NOT NULL AND task_id IS NOT NULL;

-- These are cached command responses, not creative content. Remove exact command
-- scopes, resource IDs and known identity fields in nested JSON objects. User
-- content remains content even when a text field exactly equals an old UUID.
DELETE FROM idempotency_record receipt
WHERE EXISTS (
    SELECT 1 FROM project p
    WHERE receipt.scope IN ('project:' || p.id || ':create-agent',
                            'project:' || p.id || ':create-run')
)
OR EXISTS (
    SELECT 1 FROM agent_instance a
    WHERE receipt.scope = 'agent:' || a.id || ':create-conversation'
)
OR receipt.resource_id IN (SELECT id FROM director_reset_identity)
OR EXISTS (
    SELECT 1
    FROM jsonb_path_query(receipt.response_json, '$.** ? (@.type() == "object")') object_value
    CROSS JOIN LATERAL jsonb_each(object_value) identity_field
    JOIN director_reset_identity legacy ON identity_field.value = to_jsonb(legacy.id::text)
    WHERE identity_field.key IN ('agentId', 'agentInstanceId', 'conversationId',
                                  'currentConversationId', 'runId', 'taskId', 'approvalId')
);

-- Public Agent stream deltas are execution history too. Preserve all creative
-- canvas/artifact/usage events, but do not allow an SSE replay to reveal old chat.
DELETE FROM project_event
WHERE type LIKE 'agent.%' OR type LIKE 'llm.turn.%'
   OR (type = 'task.status.changed' AND aggregate_id IN (SELECT id FROM director_reset_task));

-- call_log_debug is deleted by its existing ON DELETE CASCADE foreign key.
DELETE FROM call_log WHERE run_id IS NOT NULL
    OR task_id IN (SELECT id FROM director_reset_task);
DELETE FROM tool_execution;
DELETE FROM llm_turn;
DELETE FROM agent_media_approval;
DELETE FROM provider_attempt WHERE task_id IN (SELECT id FROM director_reset_task);
DELETE FROM task_artifact_target WHERE task_id IN (SELECT id FROM director_reset_task);
DELETE FROM task_late_result WHERE task_id IN (SELECT id FROM director_reset_task);
-- A replacement edge can cross into a direct task. Remove the edge, retaining
-- the direct task and all of its own provider/target/result records.
DELETE FROM task_manual_replacement
WHERE original_task_id IN (SELECT id FROM director_reset_task)
   OR replacement_task_id IN (SELECT id FROM director_reset_task);
DELETE FROM task_provider_poll_retry WHERE task_id IN (SELECT id FROM director_reset_task);
DELETE FROM task WHERE id IN (SELECT id FROM director_reset_task);

UPDATE project SET active_run_id = NULL
WHERE active_run_id IN (SELECT id FROM agent_run);
DELETE FROM agent_run;
DELETE FROM agent_conversation;
-- Existing cascades remove Skill bindings and their command receipts. Skill
-- catalogue entries and immutable Skill versions remain available to the user.
DELETE FROM agent_instance;

-- Do not rewrite artifact_version.run_id or usage_ledger identifiers: versions
-- are immutable, and financial history intentionally has no execution FK.
-- Allocate a fresh cursor under each project row lock. Never rewind event_seq
-- after deleting old events, or reconnecting clients could miss later changes.
WITH advanced AS (
    UPDATE project p SET event_seq = p.event_seq + 1
    FROM director_reset_project changed
    WHERE p.id = changed.project_id
    RETURNING p.id, p.event_seq
)
INSERT INTO project_event(project_id, seq, event_id, type, schema_version,
                          aggregate_id, aggregate_version, payload_json, occurred_at)
SELECT id, event_seq, gen_random_uuid(), 'agent.instance.changed', 1,
       id, 0, '{"reset":true}'::jsonb, now()
FROM advanced;
