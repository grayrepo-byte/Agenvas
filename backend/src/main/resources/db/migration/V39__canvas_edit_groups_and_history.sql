-- Presentation groups and bounded edit receipts are independent from creative content.
CREATE TABLE public.canvas_group (
    project_id uuid NOT NULL,
    id uuid NOT NULL,
    name varchar(160) NOT NULL,
    collapsed boolean NOT NULL DEFAULT false,
    deleted boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT canvas_group_pkey PRIMARY KEY (project_id, id),
    CONSTRAINT fk_canvas_group_project FOREIGN KEY (project_id) REFERENCES public.project(id),
    CONSTRAINT ck_canvas_group_name CHECK (length(btrim(name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_canvas_group_version CHECK (version >= 0)
);
COMMENT ON TABLE public.canvas_group IS '项目内命名、折叠的画布展示分组；解组保留墓碑以避免身份和版本重用';
COMMENT ON COLUMN public.canvas_group.project_id IS '唯一所属项目';
COMMENT ON COLUMN public.canvas_group.id IS '稳定分组身份';
COMMENT ON COLUMN public.canvas_group.name IS '用户可编辑的展示名称';
COMMENT ON COLUMN public.canvas_group.collapsed IS '持久折叠投影；不改变成员布局或内容';
COMMENT ON COLUMN public.canvas_group.deleted IS '解组墓碑；活动节点不得引用已解组组';
COMMENT ON COLUMN public.canvas_group.version IS '分组元数据的乐观锁版本；恢复仍单调递增';
COMMENT ON COLUMN public.canvas_group.created_at IS '首次建立分组的时间';
COMMENT ON COLUMN public.canvas_group.updated_at IS '最近一次元数据变化时间';
COMMENT ON CONSTRAINT canvas_group_pkey ON public.canvas_group IS '分组身份在同一项目内唯一';
COMMENT ON INDEX public.canvas_group_pkey IS '按项目和分组身份定位元数据及墓碑';
COMMENT ON CONSTRAINT fk_canvas_group_project ON public.canvas_group IS '分组必须属于现存项目';
COMMENT ON CONSTRAINT ck_canvas_group_name ON public.canvas_group IS '分组名称去空格后非空且不超过80个Unicode码点';
COMMENT ON CONSTRAINT ck_canvas_group_version ON public.canvas_group IS '分组版本不可为负';
INSERT INTO public.canvas_group(project_id,id,name,collapsed,deleted,version,created_at,updated_at)
SELECT project_id, group_id, 'Group', false, false, 0, min(created_at), max(updated_at)
FROM public.canvas_item WHERE group_id IS NOT NULL GROUP BY project_id,group_id;
ALTER TABLE public.canvas_item ADD CONSTRAINT fk_canvas_item_group
    FOREIGN KEY (project_id,group_id) REFERENCES public.canvas_group(project_id,id);
COMMENT ON CONSTRAINT fk_canvas_item_group ON public.canvas_item IS '节点分组必须属于同一项目；删除组以墓碑保存身份';

CREATE TABLE public.canvas_edit_receipt (
    project_id uuid NOT NULL,
    id uuid NOT NULL,
    operation varchar(16) NOT NULL,
    state varchar(16) NOT NULL,
    revision bigint NOT NULL,
    content_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT canvas_edit_receipt_pkey PRIMARY KEY (project_id,id),
    CONSTRAINT fk_canvas_edit_receipt_project FOREIGN KEY (project_id) REFERENCES public.project(id),
    CONSTRAINT ck_canvas_edit_receipt_operation CHECK (operation IN ('DISPLAY','GROUP','DUPLICATE')),
    CONSTRAINT ck_canvas_edit_receipt_state CHECK (state IN ('APPLIED','UNDONE')),
    CONSTRAINT ck_canvas_edit_receipt_revision CHECK (revision >= 0),
    CONSTRAINT ck_canvas_edit_receipt_content CHECK (jsonb_typeof(content_json) = 'object' AND content_json @> '{"schemaVersion":1}'::jsonb)
);
COMMENT ON TABLE public.canvas_edit_receipt IS '每项目最近100次展示编辑的撤销重做凭据；不承担内容或任务回放';
COMMENT ON COLUMN public.canvas_edit_receipt.project_id IS '唯一所属项目';
COMMENT ON COLUMN public.canvas_edit_receipt.id IS '原始编辑的稳定命令身份，同时是会话历史凭据身份';
COMMENT ON COLUMN public.canvas_edit_receipt.operation IS '展示、分组或独立工作分支复制';
COMMENT ON COLUMN public.canvas_edit_receipt.state IS '当前应用或撤销方向';
COMMENT ON COLUMN public.canvas_edit_receipt.revision IS '每次撤销或重做递增的凭据CAS版本';
COMMENT ON COLUMN public.canvas_edit_receipt.content_json IS 'Schema1的前后展示快照、固定复制恢复数据及安全删除前提；不含凭据或任务正文';
COMMENT ON COLUMN public.canvas_edit_receipt.created_at IS '原始编辑提交时间；决定有界历史淘汰顺序';
COMMENT ON CONSTRAINT canvas_edit_receipt_pkey ON public.canvas_edit_receipt IS '命令身份在项目中唯一';
COMMENT ON INDEX public.canvas_edit_receipt_pkey IS '按项目和凭据身份读取撤销前提';
COMMENT ON CONSTRAINT fk_canvas_edit_receipt_project ON public.canvas_edit_receipt IS '编辑历史必须属于现存项目';
COMMENT ON CONSTRAINT ck_canvas_edit_receipt_operation ON public.canvas_edit_receipt IS '仅保存明确支持恢复的画布操作';
COMMENT ON CONSTRAINT ck_canvas_edit_receipt_state ON public.canvas_edit_receipt IS '凭据状态只允许已应用和已撤销';
COMMENT ON CONSTRAINT ck_canvas_edit_receipt_revision ON public.canvas_edit_receipt IS '凭据版本从零单调递增';
COMMENT ON CONSTRAINT ck_canvas_edit_receipt_content ON public.canvas_edit_receipt IS '持久快照必须声明Schema1';
CREATE INDEX canvas_edit_receipt_retention_idx ON public.canvas_edit_receipt(project_id,created_at DESC,id);
COMMENT ON INDEX public.canvas_edit_receipt_retention_idx IS '稳定保留每项目最近100份凭据并删除更早历史';

CREATE TABLE public.canvas_edit_command (
    project_id uuid NOT NULL,
    command_id uuid NOT NULL,
    receipt_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    CONSTRAINT canvas_edit_command_pkey PRIMARY KEY(project_id,command_id),
    CONSTRAINT fk_canvas_edit_command_project FOREIGN KEY(project_id) REFERENCES public.project(id),
    CONSTRAINT ck_canvas_edit_command_hash CHECK (length(request_hash) = 64)
);
COMMENT ON TABLE public.canvas_edit_command IS '持久紧凑的编辑命令幂等墓碑；历史快照过期后仍拒绝迟到重试重新执行';
COMMENT ON COLUMN public.canvas_edit_command.project_id IS '所属项目及凭据范围';
COMMENT ON COLUMN public.canvas_edit_command.command_id IS '客户端在重试中复用的命令身份';
COMMENT ON COLUMN public.canvas_edit_command.receipt_id IS '本命令操作的历史凭据弱引用；快照过期后保留原身份并返回过期冲突';
COMMENT ON COLUMN public.canvas_edit_command.request_hash IS '完整请求的SHA256；同身份异参拒绝';
COMMENT ON CONSTRAINT canvas_edit_command_pkey ON public.canvas_edit_command IS '每个项目的命令身份唯一';
COMMENT ON INDEX public.canvas_edit_command_pkey IS '查找已受理命令以避免重复变更';
COMMENT ON CONSTRAINT fk_canvas_edit_command_project ON public.canvas_edit_command IS '命令墓碑必须属于现存项目；不随快照过期删除';
COMMENT ON CONSTRAINT ck_canvas_edit_command_hash ON public.canvas_edit_command IS '限定SHA256十六进制指纹长度';


-- A connection created then removed must still fence clone undo at both endpoints.
ALTER TABLE public.canvas_item ADD COLUMN topology_epoch bigint NOT NULL DEFAULT 0;
ALTER TABLE public.canvas_item ADD CONSTRAINT ck_canvas_item_topology_epoch CHECK (topology_epoch >= 0);
COMMENT ON COLUMN public.canvas_item.topology_epoch IS '任何关联连线创建、修改、删除时递增；与展示版本和内容选择epoch独立，防止连线变化后误撤销复制';
COMMENT ON CONSTRAINT ck_canvas_item_topology_epoch ON public.canvas_item IS '拓扑修改世代从零单调递增';
CREATE FUNCTION public.advance_canvas_connection_topology_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    endpoints uuid[];
    scope_project uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        endpoints := ARRAY[NEW.source_canvas_item_id, NEW.target_canvas_item_id];
        scope_project := NEW.project_id;
    ELSIF TG_OP = 'DELETE' THEN
        endpoints := ARRAY[OLD.source_canvas_item_id, OLD.target_canvas_item_id];
        scope_project := OLD.project_id;
    ELSIF OLD.project_id <> NEW.project_id THEN
        UPDATE public.canvas_item SET topology_epoch = topology_epoch + 1
            WHERE project_id = OLD.project_id AND id = ANY(ARRAY[OLD.source_canvas_item_id, OLD.target_canvas_item_id]);
        endpoints := ARRAY[NEW.source_canvas_item_id, NEW.target_canvas_item_id];
        scope_project := NEW.project_id;
    ELSE
        endpoints := ARRAY[OLD.source_canvas_item_id, OLD.target_canvas_item_id,
                           NEW.source_canvas_item_id, NEW.target_canvas_item_id];
        scope_project := NEW.project_id;
    END IF;
    UPDATE public.canvas_item SET topology_epoch = topology_epoch + 1
        WHERE project_id = scope_project AND id = ANY(endpoints);
    RETURN NULL;
END;
$$;
COMMENT ON FUNCTION public.advance_canvas_connection_topology_epoch() IS '同事务递增连线所有现存端点的拓扑世代；覆盖显式改线、来源清理与外键级联删除';
CREATE TRIGGER canvas_connection_topology_epoch AFTER INSERT OR UPDATE OR DELETE ON public.canvas_connection
    FOR EACH ROW EXECUTE FUNCTION public.advance_canvas_connection_topology_epoch();
COMMENT ON TRIGGER canvas_connection_topology_epoch ON public.canvas_connection IS '连接变化不会因后来恢复相同连线列表而丢失撤销安全前提';
