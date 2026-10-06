-- Preserve existing fixed versions while allowing an ordered Skill catalogue per Agent.
ALTER TABLE agent_skill_binding DROP CONSTRAINT agent_skill_binding_pkey;
ALTER TABLE agent_skill_binding ADD COLUMN position integer NOT NULL DEFAULT 0;
ALTER TABLE agent_skill_binding ADD CONSTRAINT agent_skill_binding_pkey PRIMARY KEY (agent_id, skill_id);
ALTER TABLE agent_skill_binding ADD CONSTRAINT agent_skill_binding_position_check CHECK (position BETWEEN 0 AND 7);
ALTER TABLE agent_skill_binding ADD CONSTRAINT agent_skill_binding_position_key UNIQUE (agent_id, position);
COMMENT ON TABLE agent_skill_binding IS 'Agent selected immutable Skill versions, ordered for the available catalogue';
COMMENT ON COLUMN agent_skill_binding.position IS 'Zero-based position in the Agent default Skill selection; at most eight';
COMMENT ON CONSTRAINT agent_skill_binding_pkey ON agent_skill_binding IS 'One fixed version of each Skill per Agent';
COMMENT ON INDEX agent_skill_binding_pkey IS 'Supports unique Agent and Skill bindings';
COMMENT ON CONSTRAINT agent_skill_binding_position_check ON agent_skill_binding IS 'Bounds each Agent catalogue to eight positions';
COMMENT ON CONSTRAINT agent_skill_binding_position_key ON agent_skill_binding IS 'Unique default Skill position within an Agent';
COMMENT ON INDEX agent_skill_binding_position_key IS 'Supports ordered default Skill selection';
