-- Existing fixed selections keep their versions and positions.
ALTER TABLE agent_skill_binding DROP CONSTRAINT agent_skill_binding_position_check;
ALTER TABLE agent_skill_binding ADD CONSTRAINT agent_skill_binding_position_check CHECK (position BETWEEN 0 AND 99);
COMMENT ON COLUMN agent_skill_binding.position IS 'Zero-based position in the Agent default Skill selection; at most one hundred';
COMMENT ON CONSTRAINT agent_skill_binding_position_check ON agent_skill_binding IS 'Bounds each Agent catalogue to one hundred positions';

-- Each account registers the packaged catalogue independently, retaining ordinary ownership FKs.
ALTER TABLE creative_skill ADD COLUMN builtin_key varchar(200);
ALTER TABLE creative_skill ADD CONSTRAINT creative_skill_builtin_key_check CHECK (builtin_key IS NULL OR length(btrim(builtin_key)) > 0);
ALTER TABLE creative_skill ADD CONSTRAINT creative_skill_owner_builtin_key_key UNIQUE (owner_id, builtin_key);
COMMENT ON COLUMN creative_skill.builtin_key IS 'Packaged read-only Skill identity; null for user-authored Skills';
COMMENT ON CONSTRAINT creative_skill_builtin_key_check ON creative_skill IS 'Builtin identities cannot be empty';
COMMENT ON CONSTRAINT creative_skill_owner_builtin_key_key ON creative_skill IS 'Idempotent packaged Skill registration within an account';
COMMENT ON INDEX creative_skill_owner_builtin_key_key IS 'Supports concurrent account-local builtin registration';
