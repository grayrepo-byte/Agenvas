CREATE INDEX ix_task_observable_states ON task (status)
    WHERE status IN ('READY', 'UNKNOWN', 'BLOCKED');

COMMENT ON INDEX ix_task_observable_states IS
    'Supports bounded-cardinality read-only queue diagnostics without scanning historical tasks.';
