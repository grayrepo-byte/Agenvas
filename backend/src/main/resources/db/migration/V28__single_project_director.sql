-- V27 removes the explicitly retired multi-assistant identities and history.
-- All future creation must obey the single, stable director identity per project.
ALTER TABLE agent_instance
    ADD CONSTRAINT uq_agent_instance_project UNIQUE (project_id);

COMMENT ON CONSTRAINT uq_agent_instance_project ON agent_instance
    IS '唯一约束：每个项目最多保留一个稳定的导演身份';
COMMENT ON INDEX uq_agent_instance_project
    IS '支撑唯一约束 agent_instance.uq_agent_instance_project';
COMMENT ON TABLE agent_instance
    IS '项目唯一导演配置；会话、Run 和 Skill 通过稳定身份关联，配置修改使用 CAS';
