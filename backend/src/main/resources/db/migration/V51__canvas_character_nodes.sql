-- Character cards have their own project identity and never pretend to be Artifacts.
ALTER TABLE canvas_item ALTER COLUMN artifact_id DROP NOT NULL;
ALTER TABLE canvas_item ADD COLUMN character_id uuid;
ALTER TABLE canvas_item DROP CONSTRAINT ck_canvas_item_subject_type;
ALTER TABLE canvas_item DROP CONSTRAINT ck_canvas_item_subject_mapping;
ALTER TABLE canvas_item ADD CONSTRAINT ck_canvas_item_subject_type CHECK (subject_type IN ('ARTIFACT','CHARACTER'));
ALTER TABLE canvas_item ADD CONSTRAINT ck_canvas_item_subject_mapping CHECK (
 (subject_type='ARTIFACT' AND artifact_id IS NOT NULL AND subject_id=artifact_id AND character_id IS NULL)
 OR (subject_type='CHARACTER' AND artifact_id IS NULL AND character_id IS NOT NULL AND subject_id=character_id AND selected_version_id IS NULL));
ALTER TABLE canvas_item ADD CONSTRAINT fk_canvas_item_character FOREIGN KEY(project_id,character_id) REFERENCES project_character(project_id,id);
COMMENT ON COLUMN canvas_item.character_id IS '角色节点的同项目档案身份；产物节点为空';
COMMENT ON COLUMN canvas_item.artifact_id IS '产物节点的同项目内容身份；角色节点为空';
COMMENT ON COLUMN canvas_item.subject_type IS '画布业务主体：ARTIFACT或CHARACTER';
COMMENT ON COLUMN canvas_item.subject_id IS '同项目产物或角色档案身份，由主体类型决定';
COMMENT ON CONSTRAINT ck_canvas_item_subject_type ON canvas_item IS '只允许产物和独立角色节点';
COMMENT ON CONSTRAINT ck_canvas_item_subject_mapping ON canvas_item IS '主体身份与唯一对应外键一致；角色节点无媒体选用版本';
COMMENT ON CONSTRAINT fk_canvas_item_character ON canvas_item IS '角色节点不能跨项目引用档案';

CREATE TABLE canvas_character_node (
 project_id uuid NOT NULL,
 canvas_item_id uuid NOT NULL,
 character_id uuid NOT NULL,
 character_version bigint NOT NULL,
 selection_json jsonb NOT NULL,
 version bigint NOT NULL DEFAULT 0,
 CONSTRAINT canvas_character_node_pkey PRIMARY KEY(project_id,canvas_item_id),
 CONSTRAINT fk_canvas_character_node_item FOREIGN KEY(project_id,canvas_item_id) REFERENCES canvas_item(project_id,id) ON DELETE CASCADE,
 CONSTRAINT fk_canvas_character_node_revision FOREIGN KEY(project_id,character_id,character_version) REFERENCES project_character_revision(project_id,character_id,version),
 CONSTRAINT ck_canvas_character_node_version CHECK(version>=0 AND character_version>=1),
 CONSTRAINT ck_canvas_character_node_json CHECK(selection_json->>'schemaVersion'='1')
);
COMMENT ON TABLE canvas_character_node IS '角色节点独立选择：固定项目修订、造型和本次参考，不改写媒体草稿';
COMMENT ON COLUMN canvas_character_node.project_id IS '项目授权作用域';
COMMENT ON COLUMN canvas_character_node.canvas_item_id IS '独立画布节点身份';
COMMENT ON COLUMN canvas_character_node.character_id IS '固定项目角色身份';
COMMENT ON COLUMN canvas_character_node.character_version IS '固定不可变角色修订号';
COMMENT ON COLUMN canvas_character_node.selection_json IS 'schemaVersion=1的造型、有序图片子集和外观声音用途';
COMMENT ON COLUMN canvas_character_node.version IS '选择配置CAS版本，与布局版本独立';
COMMENT ON CONSTRAINT canvas_character_node_pkey ON canvas_character_node IS '每个画布角色节点只有一个选择配置';
COMMENT ON CONSTRAINT fk_canvas_character_node_item ON canvas_character_node IS '删除画布节点清理配置';
COMMENT ON CONSTRAINT fk_canvas_character_node_revision ON canvas_character_node IS '固定选择必须对应真实同项目不可变角色修订';
COMMENT ON CONSTRAINT ck_canvas_character_node_version ON canvas_character_node IS '配置版本非负、角色修订为正';
COMMENT ON CONSTRAINT ck_canvas_character_node_json ON canvas_character_node IS '只读取已知配置JSON版本';
COMMENT ON INDEX canvas_character_node_pkey IS '按项目和节点读取固定配置';

CREATE TABLE canvas_character_connection (
 id uuid NOT NULL,
 project_id uuid NOT NULL,
 source_canvas_item_id uuid NOT NULL,
 target_canvas_item_id uuid NOT NULL,
 version bigint NOT NULL DEFAULT 0,
 CONSTRAINT canvas_character_connection_pkey PRIMARY KEY(id),
 CONSTRAINT uq_canvas_character_connection_identity UNIQUE(project_id,source_canvas_item_id,target_canvas_item_id),
 CONSTRAINT fk_canvas_character_connection_source FOREIGN KEY(project_id,source_canvas_item_id) REFERENCES canvas_character_node(project_id,canvas_item_id) ON DELETE CASCADE,
 CONSTRAINT fk_canvas_character_connection_target FOREIGN KEY(project_id,target_canvas_item_id) REFERENCES canvas_item(project_id,id) ON DELETE CASCADE,
 CONSTRAINT ck_canvas_character_connection_distinct CHECK(source_canvas_item_id<>target_canvas_item_id),
 CONSTRAINT ck_canvas_character_connection_version CHECK(version>=0)
);
COMMENT ON TABLE canvas_character_connection IS '角色应用逻辑关系；不生成、不自动修改输入、不冒充图片首帧或执行DAG';
COMMENT ON COLUMN canvas_character_connection.id IS '客户端生成的关系身份，重试复用';
COMMENT ON COLUMN canvas_character_connection.project_id IS '项目授权作用域';
COMMENT ON COLUMN canvas_character_connection.source_canvas_item_id IS '固定角色节点来源';
COMMENT ON COLUMN canvas_character_connection.target_canvas_item_id IS '图片或视频草稿目标';
COMMENT ON COLUMN canvas_character_connection.version IS '关系删除CAS版本';
COMMENT ON CONSTRAINT canvas_character_connection_pkey ON canvas_character_connection IS '逻辑关系唯一身份';
COMMENT ON CONSTRAINT uq_canvas_character_connection_identity ON canvas_character_connection IS '相同角色节点与目标不重复连接';
COMMENT ON CONSTRAINT fk_canvas_character_connection_source ON canvas_character_connection IS '来源必须是同项目角色节点';
COMMENT ON CONSTRAINT fk_canvas_character_connection_target ON canvas_character_connection IS '目标必须是同项目画布节点';
COMMENT ON CONSTRAINT ck_canvas_character_connection_distinct ON canvas_character_connection IS '逻辑关系不允许自环';
COMMENT ON CONSTRAINT ck_canvas_character_connection_version ON canvas_character_connection IS '关系版本非负';
COMMENT ON INDEX canvas_character_connection_pkey IS '按身份进行幂等重试和CAS断开';
COMMENT ON INDEX uq_canvas_character_connection_identity IS '同项目有序逻辑拓扑查询与去重';
CREATE TRIGGER canvas_character_connection_topology_epoch AFTER INSERT OR UPDATE OR DELETE ON canvas_character_connection
 FOR EACH ROW EXECUTE FUNCTION advance_canvas_connection_topology_epoch();
COMMENT ON TRIGGER canvas_character_connection_topology_epoch ON canvas_character_connection IS '角色逻辑连线同样递增端点拓扑世代，防止先连后断绕过复制撤销保护';

CREATE TABLE canvas_character_command (
 project_id uuid NOT NULL,
 command_id uuid NOT NULL,
 request_hash varchar(64) NOT NULL,
 result_json jsonb NOT NULL,
 CONSTRAINT canvas_character_command_pkey PRIMARY KEY(project_id,command_id),
 CONSTRAINT fk_canvas_character_command_project FOREIGN KEY(project_id) REFERENCES project(id) ON DELETE CASCADE,
 CONSTRAINT ck_canvas_character_command_hash CHECK(length(request_hash)=64)
);
COMMENT ON TABLE canvas_character_command IS '明确角色节点应用的持久回执；重放不重写草稿或创建任务';
COMMENT ON COLUMN canvas_character_command.project_id IS '项目授权作用域';
COMMENT ON COLUMN canvas_character_command.command_id IS '客户端显式应用命令身份';
COMMENT ON COLUMN canvas_character_command.request_hash IS '规范JSON请求摘要，异参重放拒绝';
COMMENT ON COLUMN canvas_character_command.result_json IS 'schemaVersion=1的原成功应用结果，不含凭据';
COMMENT ON CONSTRAINT canvas_character_command_pkey ON canvas_character_command IS '同项目命令只完成一次';
COMMENT ON CONSTRAINT fk_canvas_character_command_project ON canvas_character_command IS '项目删除清理应用回执';
COMMENT ON CONSTRAINT ck_canvas_character_command_hash ON canvas_character_command IS 'SHA256摘要长度约束';
COMMENT ON INDEX canvas_character_command_pkey IS '同项目命令幂等查找';

ALTER TABLE canvas_character_binding ADD COLUMN node_character_ids_json jsonb NOT NULL DEFAULT '{"schemaVersion":1,"content":[]}'::jsonb;
COMMENT ON COLUMN canvas_character_binding.node_character_ids_json IS 'schemaVersion=1：上次明确节点应用拥有的角色身份；普通手动应用清空来源，节点应用保留其他角色';
