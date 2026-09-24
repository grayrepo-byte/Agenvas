ALTER TABLE provider_attempt
    ADD COLUMN candidate_request_id uuid;

ALTER TABLE provider_attempt
    ADD CONSTRAINT ck_provider_attempt_candidate_request_id
        CHECK (candidate_request_id IS NULL OR candidate_request_id = request_key);

COMMENT ON COLUMN provider_attempt.candidate_request_id IS
    'Only set when the committed request key was sent as the provider-assigned lookup ID; legacy attempts remain NULL.';
