ALTER TABLE provider_attempt
    DROP CONSTRAINT ck_provider_attempt_status;

ALTER TABLE provider_attempt
    ADD CONSTRAINT ck_provider_attempt_status
    CHECK (status IN ('SUBMITTING', 'ACCEPTED', 'UNKNOWN', 'REJECTED'));

COMMENT ON COLUMN provider_attempt.status IS
    'REJECTED is an explicit provider rejection; uncertain transport failures remain UNKNOWN.';
