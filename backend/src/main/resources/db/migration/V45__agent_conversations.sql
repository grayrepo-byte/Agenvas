CREATE TABLE agent_conversation (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    agent_instance_id uuid NOT NULL,
    title varchar(160) NOT NULL,
    turn_count bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_conversation_agent_scope UNIQUE (project_id, agent_instance_id, id),
    CONSTRAINT fk_conversation_agent FOREIGN KEY (project_id, agent_instance_id)
        REFERENCES agent_instance (project_id, id),
    CONSTRAINT ck_conversation_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_conversation_turn_count CHECK (turn_count >= 0),
    CONSTRAINT ck_conversation_version CHECK (version >= 0)
);

ALTER TABLE agent_run ADD COLUMN conversation_id uuid, ADD COLUMN conversation_turn bigint;
ALTER TABLE agent_instance ADD COLUMN current_conversation_id uuid;

-- Historical Runs were independent tasks. Preserve that fact instead of inventing shared memory.
INSERT INTO agent_conversation (id, project_id, agent_instance_id, title,
        turn_count, version, created_at, updated_at)
SELECT id, project_id, agent_instance_id, left(instruction, 40), 1, 1, created_at, updated_at
FROM agent_run;

UPDATE agent_run SET conversation_id = id, conversation_turn = 1;
UPDATE agent_instance a SET current_conversation_id = (
    SELECT r.conversation_id FROM agent_run r
    WHERE r.project_id = a.project_id AND r.agent_instance_id = a.id
    ORDER BY r.created_at DESC, r.id DESC LIMIT 1
);

ALTER TABLE agent_run
    ALTER COLUMN conversation_id SET NOT NULL,
    ALTER COLUMN conversation_turn SET NOT NULL,
    ADD CONSTRAINT fk_run_conversation FOREIGN KEY (project_id, agent_instance_id, conversation_id)
        REFERENCES agent_conversation (project_id, agent_instance_id, id),
    ADD CONSTRAINT uq_run_conversation_turn UNIQUE (conversation_id, conversation_turn),
    ADD CONSTRAINT ck_run_conversation_turn CHECK (conversation_turn > 0);

ALTER TABLE agent_instance
    ADD CONSTRAINT fk_agent_current_conversation FOREIGN KEY (project_id, id, current_conversation_id)
        REFERENCES agent_conversation (project_id, agent_instance_id, id);

CREATE INDEX ix_conversation_agent_updated
    ON agent_conversation (project_id, agent_instance_id, updated_at DESC, id DESC);

COMMENT ON TABLE agent_conversation IS
    'Persistent user conversation. Each accepted message starts a separately budgeted AgentRun.';
COMMENT ON COLUMN agent_instance.current_conversation_id IS
    'Selected conversation only; switching it never cancels or reassigns an active Run.';
