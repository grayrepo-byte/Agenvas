ALTER TABLE canvas_connection DROP CONSTRAINT ck_canvas_connection_type;
ALTER TABLE canvas_connection ADD CONSTRAINT ck_canvas_connection_type
    CHECK (relation_type IN ('MEDIA_INPUT', 'MEDIA_DERIVATION', 'PROMPT_INPUT'));
COMMENT ON CONSTRAINT ck_canvas_connection_type ON canvas_connection IS '精确媒体输入、媒体派生或文本节点公共提示词关系';
COMMENT ON COLUMN canvas_connection.relation_type IS 'MEDIA_INPUT 固定媒体版本；MEDIA_DERIVATION 为来源提示；PROMPT_INPUT 在生成受理时读取文本节点已保存正文';
COMMENT ON COLUMN canvas_connection.source_artifact_version_id IS '建立连线时的来源版本；PROMPT_INPUT 仅作建连审计，执行时冻结文本节点当时的正文版本';
