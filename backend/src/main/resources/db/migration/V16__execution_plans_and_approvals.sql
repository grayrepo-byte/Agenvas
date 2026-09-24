CREATE TABLE execution_plan (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    revision integer NOT NULL,
    stage varchar(16) NOT NULL,
    status varchar(20) NOT NULL,
    objective varchar(1000) NOT NULL,
    plan_json jsonb NOT NULL,
    input_snapshot_json jsonb NOT NULL,
    input_snapshot_hash char(64) NOT NULL,
    plan_hash char(64) NOT NULL,
    provider_config_version integer NOT NULL,
    workflow_version varchar(120) NOT NULL,
    estimate_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_execution_plan_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_execution_plan_project_run_id UNIQUE (project_id, run_id, id),
    CONSTRAINT uq_execution_plan_revision UNIQUE (run_id, stage, revision),
    CONSTRAINT fk_execution_plan_run FOREIGN KEY (project_id, run_id)
        REFERENCES agent_run (project_id, id),
    CONSTRAINT ck_execution_plan_revision CHECK (revision > 0),
    CONSTRAINT ck_execution_plan_stage CHECK (stage IN ('IMAGE', 'VIDEO')),
    CONSTRAINT ck_execution_plan_status CHECK
        (status IN ('PENDING', 'APPROVED', 'REJECTED', 'STALE')),
    CONSTRAINT ck_execution_plan_objective CHECK (length(btrim(objective)) > 0),
    CONSTRAINT ck_execution_plan_json CHECK (jsonb_typeof(plan_json) = 'object'
        AND jsonb_typeof(input_snapshot_json) = 'object'
        AND jsonb_typeof(estimate_json) = 'object'),
    CONSTRAINT ck_execution_plan_hashes CHECK (
        input_snapshot_hash ~ '^[0-9a-f]{64}$' AND plan_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_execution_plan_provider_version CHECK (provider_config_version > 0),
    CONSTRAINT ck_execution_plan_workflow CHECK (length(btrim(workflow_version)) > 0)
);

CREATE TABLE plan_step (
    plan_id uuid NOT NULL,
    project_id uuid NOT NULL,
    step_key varchar(160) NOT NULL,
    ordinal integer NOT NULL,
    kind varchar(40) NOT NULL,
    shot_artifact_id uuid NOT NULL,
    shot_version_id uuid NOT NULL,
    image_artifact_id uuid,
    image_version_id uuid,
    output_slot_key varchar(160) NOT NULL,
    input_json jsonb NOT NULL,
    dependency_keys_json jsonb NOT NULL,
    PRIMARY KEY (plan_id, step_key),
    CONSTRAINT uq_plan_step_ordinal UNIQUE (plan_id, ordinal),
    CONSTRAINT uq_plan_step_output UNIQUE (plan_id, output_slot_key),
    CONSTRAINT fk_plan_step_plan FOREIGN KEY (project_id, plan_id)
        REFERENCES execution_plan (project_id, id),
    CONSTRAINT fk_plan_step_shot FOREIGN KEY (project_id, shot_artifact_id)
        REFERENCES artifact (project_id, id),
    CONSTRAINT fk_plan_step_shot_version FOREIGN KEY (shot_artifact_id, shot_version_id)
        REFERENCES artifact_version (artifact_id, id),
    CONSTRAINT fk_plan_step_image FOREIGN KEY (project_id, image_artifact_id)
        REFERENCES artifact (project_id, id),
    CONSTRAINT fk_plan_step_image_version FOREIGN KEY (image_artifact_id, image_version_id)
        REFERENCES artifact_version (artifact_id, id),
    CONSTRAINT ck_plan_step_keys CHECK (length(btrim(step_key)) > 0
        AND length(btrim(output_slot_key)) > 0),
    CONSTRAINT ck_plan_step_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_plan_step_kind CHECK
        (kind IN ('IMAGE_GENERATION', 'VIDEO_GENERATION')),
    CONSTRAINT ck_plan_step_image_pair CHECK
        ((image_artifact_id IS NULL) = (image_version_id IS NULL)),
    CONSTRAINT ck_plan_step_json CHECK (jsonb_typeof(input_json) = 'object'
        AND jsonb_typeof(dependency_keys_json) = 'array')
);

CREATE TABLE plan_approval (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL UNIQUE,
    project_id uuid NOT NULL,
    run_id uuid NOT NULL,
    approved_by_user_id uuid NOT NULL,
    approved_plan_hash char(64) NOT NULL,
    approved_input_hash char(64) NOT NULL,
    reservation_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_plan_approval_plan FOREIGN KEY (project_id, run_id, plan_id)
        REFERENCES execution_plan (project_id, run_id, id),
    CONSTRAINT fk_plan_approval_user FOREIGN KEY (approved_by_user_id)
        REFERENCES app_user (id),
    CONSTRAINT ck_plan_approval_hashes CHECK (
        approved_plan_hash ~ '^[0-9a-f]{64}$'
        AND approved_input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_plan_approval_reservation CHECK
        (jsonb_typeof(reservation_json) = 'object')
);

ALTER TABLE task
    ADD CONSTRAINT fk_task_execution_plan FOREIGN KEY (project_id, run_id, plan_id)
        REFERENCES execution_plan (project_id, run_id, id);

ALTER TABLE task DROP CONSTRAINT uq_task_run_step_attempt;
CREATE UNIQUE INDEX uq_task_planned_step_attempt
    ON task (plan_id, step_key, attempt_no) WHERE plan_id IS NOT NULL;
CREATE UNIQUE INDEX uq_task_unplanned_step_attempt
    ON task (run_id, step_key, attempt_no) WHERE plan_id IS NULL;

CREATE INDEX ix_execution_plan_run ON execution_plan (project_id, run_id, created_at DESC);
CREATE INDEX ix_plan_step_shot ON plan_step (project_id, shot_version_id);

COMMENT ON TABLE execution_plan IS
    'Immutable proposal content and revision identity; only status may change after proposal.';
COMMENT ON TABLE plan_approval IS
    'Authenticated approval and reserved use, committed with the corresponding Tasks.';
