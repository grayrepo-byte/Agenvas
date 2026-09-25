-- Flyway runs before task schedulers. Convert only current whole-second shots by
-- appending a version; old versions and their exact references remain untouched.
ALTER TABLE export_proposal DROP CONSTRAINT ck_export_proposal_status;
ALTER TABLE export_proposal DROP CONSTRAINT ck_export_proposal_decision;
ALTER TABLE export_proposal ADD CONSTRAINT ck_export_proposal_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'STALE'));
ALTER TABLE export_proposal ADD CONSTRAINT ck_export_proposal_decision CHECK (
    (status IN ('PENDING', 'STALE') AND approved_task_id IS NULL
        AND decided_by_user_id IS NULL AND decided_at IS NULL)
    OR (status = 'APPROVED' AND approved_task_id IS NOT NULL
        AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL)
    OR (status = 'REJECTED' AND approved_task_id IS NULL
        AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL)
);

CREATE TABLE shot_duration_upgrade (
    artifact_id uuid PRIMARY KEY REFERENCES artifact(id),
    old_version_id uuid NOT NULL REFERENCES artifact_version(id),
    new_version_id uuid NOT NULL REFERENCES artifact_version(id),
    upgraded_at timestamptz NOT NULL
);

CREATE TEMP TABLE tmp_shot_duration_upgrade ON COMMIT DROP AS
SELECT a.id AS artifact_id,
       a.project_id,
       old.id AS old_version_id,
       gen_random_uuid() AS new_version_id,
       (SELECT max(v.version_no) + 1 FROM artifact_version v
        WHERE v.artifact_id = a.id) AS next_version_no,
       (old.content_json - 'durationMs') || jsonb_build_object(
           'durationSeconds', (old.content_json->>'durationMs')::integer / 1000
       ) AS new_content_json,
       old.input_refs_json,
       old.created_by_kind,
       old.run_id
FROM artifact a
JOIN artifact_version old ON old.id = a.current_version_id
WHERE a.kind = 'SHOT'
  AND old.schema_version = 1
  AND (old.content_json->>'durationMs') ~ '^[0-9]+$'
  AND (old.content_json->>'durationMs')::integer BETWEEN 1000 AND 30000
  AND (old.content_json->>'durationMs')::integer % 1000 = 0;

INSERT INTO artifact_version (
    id, project_id, artifact_id, version_no, schema_version, content_json,
    input_refs_json, created_by_kind, run_id, created_at
)
SELECT new_version_id, project_id, artifact_id, next_version_no, 2,
       new_content_json, input_refs_json, created_by_kind, run_id, now()
FROM tmp_shot_duration_upgrade;

INSERT INTO artifact_version_reference (
    source_version_id, project_id, target_version_id, reference_role, reference_order
)
SELECT migration.new_version_id, ref.project_id, ref.target_version_id,
       ref.reference_role, ref.reference_order
FROM tmp_shot_duration_upgrade migration
JOIN artifact_version_reference ref
  ON ref.source_version_id = migration.old_version_id;

INSERT INTO shot_duration_upgrade (artifact_id, old_version_id, new_version_id, upgraded_at)
SELECT artifact_id, old_version_id, new_version_id, now()
FROM tmp_shot_duration_upgrade;

UPDATE artifact a
SET current_version_id = migration.new_version_id,
    version = a.version + 1,
    updated_at = now()
FROM tmp_shot_duration_upgrade migration
WHERE a.id = migration.artifact_id
  AND a.current_version_id = migration.old_version_id;

-- Old unapproved hashes bind millisecond inputs. The user must rebuild them.
UPDATE execution_plan SET status = 'STALE', updated_at = now()
WHERE status = 'PENDING';
UPDATE export_proposal SET status = 'STALE'
WHERE status = 'PENDING';
