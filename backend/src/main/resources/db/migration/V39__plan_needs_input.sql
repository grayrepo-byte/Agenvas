ALTER TABLE execution_plan DROP CONSTRAINT ck_execution_plan_status;
ALTER TABLE execution_plan ADD CONSTRAINT ck_execution_plan_status CHECK
    (status IN ('NEEDS_INPUT', 'PENDING', 'APPROVED', 'REJECTED', 'STALE'));

ALTER TABLE plan_step ADD COLUMN adapter_id varchar(80);
ALTER TABLE plan_step ADD CONSTRAINT ck_plan_step_adapter_binding CHECK
    ((capability_id IS NULL AND adapter_id IS NULL)
        OR (capability_id IS NOT NULL AND adapter_id IS NOT NULL));
