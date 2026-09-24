-- One short row lock serializes external dispatch across application instances.
-- No transaction is kept open while ComfyUI executes or while its history is queried.
CREATE TABLE provider_dispatch_gate (
    id smallint PRIMARY KEY,
    CONSTRAINT ck_provider_dispatch_gate_singleton CHECK (id = 1)
);

INSERT INTO provider_dispatch_gate (id) VALUES (1);

COMMENT ON TABLE provider_dispatch_gate IS
    'Serializes the one-slot ComfyUI task claim; active submitted Tasks remain in task.';
