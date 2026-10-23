-- Project-level storyboard identity and append-only, versioned creative decisions.
CREATE TABLE public.storyboard (
    project_id uuid NOT NULL,
    current_version bigint NOT NULL,
    CONSTRAINT storyboard_pkey PRIMARY KEY (project_id),
    CONSTRAINT fk_storyboard_project FOREIGN KEY (project_id) REFERENCES public.project(id),
    CONSTRAINT ck_storyboard_current_version CHECK (current_version >= 1)
);
COMMENT ON TABLE public.storyboard IS '项目分镜制作台的当前修订指针；内容只存在不可变修订中';
COMMENT ON COLUMN public.storyboard.project_id IS '唯一所属项目，同时作为分镜板身份';
COMMENT ON COLUMN public.storyboard.current_version IS '当前不可变修订序号，也是全板保存的 CAS 版本';
COMMENT ON CONSTRAINT storyboard_pkey ON public.storyboard IS '每个项目至多一张分镜板';
COMMENT ON INDEX public.storyboard_pkey IS '按项目读取唯一当前分镜板';
COMMENT ON CONSTRAINT fk_storyboard_project ON public.storyboard IS '分镜板必须属于现存项目';
COMMENT ON CONSTRAINT ck_storyboard_current_version ON public.storyboard IS '持久分镜板从第一版开始；未创建板由应用投影为零版';

CREATE TABLE public.storyboard_revision (
    project_id uuid NOT NULL,
    version bigint NOT NULL,
    content_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT storyboard_revision_pkey PRIMARY KEY (project_id, version),
    CONSTRAINT fk_storyboard_revision_project FOREIGN KEY (project_id) REFERENCES public.storyboard(project_id),
    CONSTRAINT ck_storyboard_revision_version CHECK (version >= 1),
    CONSTRAINT ck_storyboard_revision_content CHECK (jsonb_typeof(content_json) = 'object' AND content_json @> '{"schemaVersion":1}'::jsonb)
);
COMMENT ON TABLE public.storyboard_revision IS '追加式分镜板修订，冻结镜头、精确版本引用与人工复核状态';
COMMENT ON COLUMN public.storyboard_revision.project_id IS '所属分镜板及项目';
COMMENT ON COLUMN public.storyboard_revision.version IS '项目内递增的全板修订序号';
COMMENT ON COLUMN public.storyboard_revision.content_json IS 'Schema 1 的镜头与成片快照，不含模型私有推理或凭据';
COMMENT ON COLUMN public.storyboard_revision.created_at IS '本次修订提交时间';
COMMENT ON CONSTRAINT storyboard_revision_pkey ON public.storyboard_revision IS '每个项目的修订序号唯一';
COMMENT ON INDEX public.storyboard_revision_pkey IS '按项目和修订序号读取当前快照及有界版本历史';
COMMENT ON CONSTRAINT fk_storyboard_revision_project ON public.storyboard_revision IS '修订必须属于分镜板';
COMMENT ON CONSTRAINT ck_storyboard_revision_version ON public.storyboard_revision IS '不可变修订从一开始';
COMMENT ON CONSTRAINT ck_storyboard_revision_content ON public.storyboard_revision IS '限定对象正文及明确的内容 Schema 版本';
CREATE FUNCTION public.reject_storyboard_revision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'storyboard_revision rows are immutable';
END;
$$;
COMMENT ON FUNCTION public.reject_storyboard_revision_mutation() IS '禁止更新或删除分镜板历史，保留已选产物和修改影响证据';
CREATE TRIGGER storyboard_revision_immutable BEFORE UPDATE OR DELETE ON public.storyboard_revision
    FOR EACH ROW EXECUTE FUNCTION public.reject_storyboard_revision_mutation();
COMMENT ON TRIGGER storyboard_revision_immutable ON public.storyboard_revision IS '所有已提交分镜板修订只允许追加';
