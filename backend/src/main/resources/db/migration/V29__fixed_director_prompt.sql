-- A project always uses the built-in director. Remove retired Agent-preset
-- definitions without rewriting the director's edited content or any FUNCTION.
DELETE FROM prompt_definition WHERE kind = 'AGENT' AND key <> 'agent.director';

ALTER TABLE prompt_definition ADD CONSTRAINT ck_prompt_fixed_director
    CHECK (kind <> 'AGENT' OR (key = 'agent.director' AND built_in));
COMMENT ON CONSTRAINT ck_prompt_fixed_director ON prompt_definition
    IS 'Agent 创作配置只保留不可删除的内置导演；其他提示词均为独立功能用途';
