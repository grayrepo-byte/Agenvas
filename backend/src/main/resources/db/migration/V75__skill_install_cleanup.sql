-- Cleanup shares installation fencing so an old cleaner cannot erase a retried preparation.
ALTER TABLE skill_install_operation DROP CONSTRAINT skill_install_operation_status_check;
ALTER TABLE skill_install_operation DROP CONSTRAINT skill_install_operation_check;
ALTER TABLE skill_install_operation ADD CONSTRAINT skill_install_operation_status_check
    CHECK (status IN ('ACCEPTED','PREPARING','SUCCEEDED','FAILED','CLEANING'));
ALTER TABLE skill_install_operation ADD CONSTRAINT skill_install_operation_lease_check
    CHECK ((status IN ('PREPARING','CLEANING')) = (lease_until IS NOT NULL));

ALTER TABLE skill_install_operation ADD COLUMN cleanup_json jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(cleanup_json) = 'array');
