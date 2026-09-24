ALTER TABLE provider_attempt
    ADD COLUMN candidate_origin_sha256 varchar(64);

ALTER TABLE provider_attempt
    ADD CONSTRAINT ck_provider_attempt_origin_sha256
        CHECK (candidate_origin_sha256 IS NULL
            OR candidate_origin_sha256 ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN provider_attempt.candidate_origin_sha256 IS
    'Hash of the exact ComfyUI origin used for a client-supplied prompt ID; NULL legacy attempts cannot be automatically reconciled.';
