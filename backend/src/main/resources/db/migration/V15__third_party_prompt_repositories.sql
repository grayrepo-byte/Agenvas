CREATE TABLE public.third_party_prompt_source (
    id varchar(80) PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9_-]{0,79}$'),
    name varchar(160) NOT NULL,
    target_kind varchar(16) NOT NULL CHECK (target_kind IN ('IMAGE', 'VIDEO')),
    format varchar(32) NOT NULL CHECK (format IN ('NATIVE_JSON', 'GITHUB_MARKDOWN', 'DAVID_JSON')),
    url text NOT NULL,
    model varchar(160) NOT NULL DEFAULT '',
    enabled boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    next_sync_at timestamptz NOT NULL DEFAULT now(),
    last_synced_at timestamptz,
    last_error varchar(80),
    lease_token uuid,
    lease_until timestamptz,
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE TABLE public.third_party_prompt (
    id text PRIMARY KEY,
    source_id varchar(80) NOT NULL REFERENCES public.third_party_prompt_source(id),
    target_kind varchar(16) NOT NULL CHECK (target_kind IN ('IMAGE', 'VIDEO')),
    title text NOT NULL,
    prompt text NOT NULL,
    data_json jsonb NOT NULL CHECK (data_json @> '{"schemaVersion":1}'::jsonb),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    cached_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (length(btrim(title)) > 0 AND length(btrim(prompt)) > 0),
    CHECK (left(id, length(source_id) + 1) = source_id || ':')
);
CREATE INDEX third_party_prompt_catalog_idx ON public.third_party_prompt (target_kind, source_id, id);
CREATE TABLE public.third_party_prompt_import (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES public.app_user(id),
    project_id uuid NOT NULL REFERENCES public.project(id),
    command_key varchar(200) NOT NULL,
    payload_hash char(64) NOT NULL,
    input_json jsonb NOT NULL,
    result_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, command_key)
);

COMMENT ON TABLE public.third_party_prompt_source IS '第三方提示词源；每日同步租约与失败状态独立持久化';
COMMENT ON TABLE public.third_party_prompt IS '规范化图片或视频提示词缓存；只新增或更新，不按上游缺失删除';
COMMENT ON TABLE public.third_party_prompt_import IS '冻结第三方模板与素材引用的幂等项目导入命令';
COMMENT ON INDEX public.third_party_prompt_catalog_idx IS '按媒体类型及来源分页读取缓存';
COMMENT ON INDEX public.third_party_prompt_source_pkey IS '按稳定来源标识查找同步配置';
COMMENT ON INDEX public.third_party_prompt_pkey IS '按来源加上游稳定标识去重';
COMMENT ON INDEX public.third_party_prompt_import_pkey IS '按固定命令身份完成导入';
COMMENT ON INDEX public.third_party_prompt_import_project_id_command_key_key IS '项目内命令键唯一，重放不重复导入';

COMMENT ON COLUMN public.third_party_prompt_source.id IS '不可变来源标识，作为提示词身份前缀';
COMMENT ON COLUMN public.third_party_prompt_source.name IS '来源显示名称';
COMMENT ON COLUMN public.third_party_prompt_source.target_kind IS '来源媒体类型：图片或视频';
COMMENT ON COLUMN public.third_party_prompt_source.format IS '上游格式适配器';
COMMENT ON COLUMN public.third_party_prompt_source.url IS '无凭证的 HTTPS 数据地址';
COMMENT ON COLUMN public.third_party_prompt_source.model IS '适配器使用的上游模型名称，仅作来源元数据';
COMMENT ON COLUMN public.third_party_prompt_source.enabled IS '停用后不再同步，缓存保留可读';
COMMENT ON COLUMN public.third_party_prompt_source.version IS '来源配置 CAS 版本';
COMMENT ON COLUMN public.third_party_prompt_source.next_sync_at IS '下次同步时间：成功后二十四小时，失败后一小时';
COMMENT ON COLUMN public.third_party_prompt_source.last_synced_at IS '最近一次成功发布缓存的时间';
COMMENT ON COLUMN public.third_party_prompt_source.last_error IS '最近失败的稳定错误码，不保存上游异常正文';
COMMENT ON COLUMN public.third_party_prompt_source.lease_token IS '当前同步的 fencing token';
COMMENT ON COLUMN public.third_party_prompt_source.lease_until IS '同步租约过期时间';
COMMENT ON COLUMN public.third_party_prompt.id IS '来源标识加上游稳定身份';
COMMENT ON COLUMN public.third_party_prompt.source_id IS '记录的第三方来源';
COMMENT ON COLUMN public.third_party_prompt.target_kind IS '规范化图片或视频类型';
COMMENT ON COLUMN public.third_party_prompt.title IS '可搜索的上游标题';
COMMENT ON COLUMN public.third_party_prompt.prompt IS '可搜索的规范化提示词正文';
COMMENT ON COLUMN public.third_party_prompt.data_json IS 'schemaVersion=1 的独立图片或视频结构';
COMMENT ON COLUMN public.third_party_prompt.version IS '内容变化时递增，导入冻结此版本';
COMMENT ON COLUMN public.third_party_prompt.cached_at IS '首次缓存时间';
COMMENT ON COLUMN public.third_party_prompt.updated_at IS '最近内容变化时间';
COMMENT ON COLUMN public.third_party_prompt_import.id IS '导入命令及素材派生的固定身份';
COMMENT ON COLUMN public.third_party_prompt_import.owner_id IS '受信任的导入账号';
COMMENT ON COLUMN public.third_party_prompt_import.project_id IS '导入目标项目';
COMMENT ON COLUMN public.third_party_prompt_import.command_key IS '项目内幂等键';
COMMENT ON COLUMN public.third_party_prompt_import.payload_hash IS '提示词身份及预期版本的请求摘要';
COMMENT ON COLUMN public.third_party_prompt_import.input_json IS '冻结的规范化模板快照';
COMMENT ON COLUMN public.third_party_prompt_import.result_json IS '原子发布的提示词及项目素材版本响应';
COMMENT ON COLUMN public.third_party_prompt_import.created_at IS '受理时间';
COMMENT ON CONSTRAINT third_party_prompt_source_pkey ON public.third_party_prompt_source IS '稳定来源身份唯一';
COMMENT ON CONSTRAINT third_party_prompt_source_id_check ON public.third_party_prompt_source IS '来源标识采用稳定小写字母数字短横线下划线';
COMMENT ON CONSTRAINT third_party_prompt_source_target_kind_check ON public.third_party_prompt_source IS '来源必须声明图片或视频';
COMMENT ON CONSTRAINT third_party_prompt_source_format_check ON public.third_party_prompt_source IS '仅接受已注册的适配器格式';
COMMENT ON CONSTRAINT third_party_prompt_source_version_check ON public.third_party_prompt_source IS '配置版本非负';
COMMENT ON CONSTRAINT third_party_prompt_source_check ON public.third_party_prompt_source IS '租约身份与过期时间同时存在或同时为空';
COMMENT ON CONSTRAINT third_party_prompt_pkey ON public.third_party_prompt IS '规范化提示词身份唯一';
COMMENT ON CONSTRAINT third_party_prompt_source_id_fkey ON public.third_party_prompt IS '缓存记录保留其来源配置';
COMMENT ON CONSTRAINT third_party_prompt_target_kind_check ON public.third_party_prompt IS '缓存类型必须为图片或视频';
COMMENT ON CONSTRAINT third_party_prompt_data_json_check ON public.third_party_prompt IS '缓存使用可迁移的第一版结构';
COMMENT ON CONSTRAINT third_party_prompt_version_check ON public.third_party_prompt IS '内容版本非负';
COMMENT ON CONSTRAINT third_party_prompt_check ON public.third_party_prompt IS '标题与提示词正文非空';
COMMENT ON CONSTRAINT third_party_prompt_check1 ON public.third_party_prompt IS '提示词身份包含真实来源前缀';
COMMENT ON CONSTRAINT third_party_prompt_import_pkey ON public.third_party_prompt_import IS '固定导入命令身份唯一';
COMMENT ON CONSTRAINT third_party_prompt_import_owner_id_fkey ON public.third_party_prompt_import IS '导入命令保留账号归属';
COMMENT ON CONSTRAINT third_party_prompt_import_project_id_fkey ON public.third_party_prompt_import IS '导入命令属于真实项目';
COMMENT ON CONSTRAINT third_party_prompt_import_project_id_command_key_key ON public.third_party_prompt_import IS '同项目命令键仅受理一次';

INSERT INTO public.third_party_prompt_source (id, name, target_kind, format, url, model) VALUES
('zerolu-gpt-image', 'ZeroLu / Awesome GPT Image', 'IMAGE', 'GITHUB_MARKDOWN', 'https://raw.githubusercontent.com/ZeroLu/awesome-gpt-image/main/README.md', 'gpt-image-2'),
('imgedify-gpt4o', 'ImgEdify / GPT4o Image Prompts', 'IMAGE', 'GITHUB_MARKDOWN', 'https://raw.githubusercontent.com/ImgEdify/Awesome-GPT4o-Image-Prompts/main/README.md', 'gpt-image-1'),
('youmind-gpt-image-2', 'YouMind / GPT Image 2', 'IMAGE', 'GITHUB_MARKDOWN', 'https://raw.githubusercontent.com/YouMind-OpenLab/awesome-gpt-image-2/main/README.md', 'gpt-image-2'),
('youmind-nano-banana-pro', 'YouMind / Nano Banana Pro', 'IMAGE', 'GITHUB_MARKDOWN', 'https://raw.githubusercontent.com/YouMind-OpenLab/awesome-nano-banana-pro-prompts/main/README.md', 'nano-banana-pro'),
('david-gpt-image-2', 'David / GPT Image 2 Prompts', 'IMAGE', 'DAVID_JSON', 'https://raw.githubusercontent.com/davidwuw0811-boop/awesome-gpt-image2-prompts/main/prompts.json', 'gpt-image-2');
