-- 基于 PostgreSQL 结构导出整理并精简的应用基线，按依赖顺序及表归组。
-- 仅用于空库；字段定义包含最终约束，不重放开发阶段的 ALTER/回填历史。
-- 所有约束在所属表内定义；当前状态指针由应用事务与归属校验维护。
-- 调用日志及用量账本的历史标识不设外键，保留原始关联身份。
-- 表和字段说明同时保留行内文档及数据库 COMMENT ON。
-- 初始化数据仅保留公开内置项，不导出用户、任务或真实配置。

-- 不可变内容保护函数。
CREATE FUNCTION public.reject_artifact_version_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'artifact_version rows are immutable';
END;
$$;
COMMENT ON FUNCTION public.reject_artifact_version_mutation() IS '拒绝更新或删除已提交的产物版本，保持内容、任务输入及审计引用不可变';

-- 本地用户、密码摘要及账户状态。
CREATE TABLE public.app_user (
    id uuid NOT NULL, -- 记录身份
    login_name character varying(100) NOT NULL, -- 本地登录名
    password_hash character varying(255) NOT NULL, -- 单向密码摘要；不保存明文密码
    status character varying(32) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    password_changed_at timestamp with time zone DEFAULT now() NOT NULL, -- Time of the latest password change; used for session and audit decisions.
    version bigint DEFAULT 0 NOT NULL, -- Optimistic version for security-sensitive administrator updates.
    CONSTRAINT ck_app_user_status CHECK ((status IN ('ACTIVE', 'DISABLED'))),
    CONSTRAINT app_user_pkey PRIMARY KEY (id),
    CONSTRAINT uq_app_user_login_name UNIQUE (login_name)
);

COMMENT ON TABLE public.app_user IS '本地用户、密码摘要及账户状态';
COMMENT ON COLUMN public.app_user.id IS '记录身份';
COMMENT ON COLUMN public.app_user.login_name IS '本地登录名';
COMMENT ON COLUMN public.app_user.password_hash IS '单向密码摘要；不保存明文密码';
COMMENT ON COLUMN public.app_user.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.app_user.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.app_user.password_changed_at IS 'Time of the latest password change; used for session and audit decisions.';
COMMENT ON COLUMN public.app_user.version IS 'Optimistic version for security-sensitive administrator updates.';
COMMENT ON CONSTRAINT ck_app_user_status ON public.app_user IS '数据有效性约束：CHECK ((status IN (''ACTIVE'', ''DISABLED'')))';
COMMENT ON CONSTRAINT app_user_pkey ON public.app_user IS '主键：唯一标识本地用户、密码摘要及账户状态的记录  (id)';
COMMENT ON CONSTRAINT uq_app_user_login_name ON public.app_user IS '唯一约束：禁止作用域内重复记录  (login_name)';
COMMENT ON INDEX public.app_user_pkey IS '支撑主键 app_user.app_user_pkey';
COMMENT ON INDEX public.uq_app_user_login_name IS '支撑唯一约束 app_user.uq_app_user_login_name';

CREATE UNIQUE INDEX uq_app_user_single_active_admin ON public.app_user USING btree (status) WHERE ((status)::text = 'ACTIVE'::text);
COMMENT ON INDEX public.uq_app_user_single_active_admin IS '唯一索引：保证限定范围内不重复；USING btree (status) WHERE ((status)::text = ''ACTIVE''::text)';

-- 全局调用调试开关，默认不保存调用正文。
CREATE TABLE public.audit_debug_settings (
    id smallint NOT NULL, -- 记录身份
    debug_mode boolean DEFAULT false NOT NULL, -- 是否显式启用已脱敏调用正文调试
    version integer DEFAULT 1 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    CONSTRAINT audit_debug_settings_id_check CHECK ((id = 1)),
    CONSTRAINT audit_debug_settings_version_check CHECK ((version > 0)),
    CONSTRAINT audit_debug_settings_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE public.audit_debug_settings IS '全局调用调试开关，默认不保存调用正文';
COMMENT ON COLUMN public.audit_debug_settings.id IS '记录身份';
COMMENT ON COLUMN public.audit_debug_settings.debug_mode IS '是否显式启用已脱敏调用正文调试';
COMMENT ON COLUMN public.audit_debug_settings.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON CONSTRAINT audit_debug_settings_id_check ON public.audit_debug_settings IS '数据有效性约束：CHECK ((id = 1))';
COMMENT ON CONSTRAINT audit_debug_settings_version_check ON public.audit_debug_settings IS '数据有效性约束：CHECK ((version > 0))';
COMMENT ON CONSTRAINT audit_debug_settings_pkey ON public.audit_debug_settings IS '主键：唯一标识全局调用调试开关，默认不保存调用正文的记录  (id)';
COMMENT ON INDEX public.audit_debug_settings_pkey IS '支撑主键 audit_debug_settings.audit_debug_settings_pkey';

-- 全局调用日志保留期限及配置版本。
CREATE TABLE public.audit_log_retention_settings (
    id smallint NOT NULL, -- 记录身份
    retention_days integer, -- 调用日志保留天数；为空表示永久，清理须管理员显式执行
    version integer DEFAULT 1 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    CONSTRAINT audit_log_retention_settings_id_check CHECK ((id = 1)),
    CONSTRAINT audit_log_retention_settings_retention_days_check CHECK (((retention_days >= 1) AND (retention_days <= 3650))),
    CONSTRAINT audit_log_retention_settings_version_check CHECK ((version > 0)),
    CONSTRAINT audit_log_retention_settings_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE public.audit_log_retention_settings IS '全局调用日志保留期限及配置版本';
COMMENT ON COLUMN public.audit_log_retention_settings.id IS '记录身份';
COMMENT ON COLUMN public.audit_log_retention_settings.retention_days IS '调用日志保留天数；为空表示永久，清理须管理员显式执行';
COMMENT ON COLUMN public.audit_log_retention_settings.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON CONSTRAINT audit_log_retention_settings_id_check ON public.audit_log_retention_settings IS '数据有效性约束：CHECK ((id = 1))';
COMMENT ON CONSTRAINT audit_log_retention_settings_retention_days_check ON public.audit_log_retention_settings IS '数据有效性约束：CHECK (((retention_days >= 1) AND (retention_days <= 3650)))';
COMMENT ON CONSTRAINT audit_log_retention_settings_version_check ON public.audit_log_retention_settings IS '数据有效性约束：CHECK ((version > 0))';
COMMENT ON CONSTRAINT audit_log_retention_settings_pkey ON public.audit_log_retention_settings IS '主键：唯一标识全局调用日志保留期限及配置版本的记录  (id)';
COMMENT ON INDEX public.audit_log_retention_settings_pkey IS '支撑主键 audit_log_retention_settings.audit_log_retention_settings_pkey';

-- 管理员初始化的单例事务锁行。
CREATE TABLE public.installation_lock (
    id smallint NOT NULL, -- 记录身份
    CONSTRAINT ck_installation_lock_singleton CHECK ((id = 1)),
    CONSTRAINT installation_lock_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE public.installation_lock IS '管理员初始化的单例事务锁行';
COMMENT ON COLUMN public.installation_lock.id IS '记录身份';
COMMENT ON CONSTRAINT ck_installation_lock_singleton ON public.installation_lock IS '数据有效性约束：CHECK ((id = 1))';
COMMENT ON CONSTRAINT installation_lock_pkey ON public.installation_lock IS '主键：唯一标识管理员初始化的单例事务锁行的记录  (id)';
COMMENT ON INDEX public.installation_lock_pkey IS '支撑主键 installation_lock.installation_lock_pkey';

-- 不可变 LLM 连接版本与加密凭据；激活标记选择当前配置。
CREATE TABLE public.llm_provider_config (
    id uuid NOT NULL, -- 记录身份
    version integer NOT NULL, -- 全局串行分配的不可变 LLM 配置版本
    endpoint character varying(500) NOT NULL, -- 管理员配置的连接地址
    model_id character varying(160) NOT NULL, -- 实际使用的模型标识
    credential_ciphertext bytea NOT NULL, -- 服务端加密凭据密文
    credential_nonce bytea NOT NULL, -- 凭据加密使用的 nonce
    key_version integer NOT NULL, -- 加密主密钥版本
    key_mask character varying(16) NOT NULL, -- 供设置页显示的凭据掩码
    tool_calling_verified boolean DEFAULT false NOT NULL, -- 模型工具调用能力已通过配置验证
    active boolean NOT NULL, -- 是否选为当前 LLM 配置
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT llm_provider_config_credential_nonce_check CHECK ((octet_length(credential_nonce) = 12)),
    CONSTRAINT llm_provider_config_key_version_check CHECK ((key_version > 0)),
    CONSTRAINT llm_provider_config_version_check CHECK ((version > 0)),
    CONSTRAINT llm_provider_config_pkey PRIMARY KEY (id),
    CONSTRAINT llm_provider_config_version_key UNIQUE (version)
);

COMMENT ON TABLE public.llm_provider_config IS '不可变 LLM 连接版本与加密凭据；激活标记选择当前配置';
COMMENT ON COLUMN public.llm_provider_config.id IS '记录身份';
COMMENT ON COLUMN public.llm_provider_config.version IS '全局串行分配的不可变 LLM 配置版本';
COMMENT ON COLUMN public.llm_provider_config.endpoint IS '管理员配置的连接地址';
COMMENT ON COLUMN public.llm_provider_config.model_id IS '实际使用的模型标识';
COMMENT ON COLUMN public.llm_provider_config.credential_ciphertext IS '服务端加密凭据密文';
COMMENT ON COLUMN public.llm_provider_config.credential_nonce IS '凭据加密使用的 nonce';
COMMENT ON COLUMN public.llm_provider_config.key_version IS '加密主密钥版本';
COMMENT ON COLUMN public.llm_provider_config.key_mask IS '供设置页显示的凭据掩码';
COMMENT ON COLUMN public.llm_provider_config.tool_calling_verified IS '模型工具调用能力已通过配置验证';
COMMENT ON COLUMN public.llm_provider_config.active IS '是否选为当前 LLM 配置';
COMMENT ON COLUMN public.llm_provider_config.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT llm_provider_config_credential_nonce_check ON public.llm_provider_config IS '数据有效性约束：CHECK ((octet_length(credential_nonce) = 12))';
COMMENT ON CONSTRAINT llm_provider_config_key_version_check ON public.llm_provider_config IS '数据有效性约束：CHECK ((key_version > 0))';
COMMENT ON CONSTRAINT llm_provider_config_version_check ON public.llm_provider_config IS '数据有效性约束：CHECK ((version > 0))';
COMMENT ON CONSTRAINT llm_provider_config_pkey ON public.llm_provider_config IS '主键：唯一标识不可变 LLM 连接版本与加密凭据的记录  (id)';
COMMENT ON CONSTRAINT llm_provider_config_version_key ON public.llm_provider_config IS '唯一约束：禁止作用域内重复记录  (version)';
COMMENT ON INDEX public.llm_provider_config_pkey IS '支撑主键 llm_provider_config.llm_provider_config_pkey';
COMMENT ON INDEX public.llm_provider_config_version_key IS '支撑唯一约束 llm_provider_config.llm_provider_config_version_key';

CREATE UNIQUE INDEX ux_llm_provider_config_active ON public.llm_provider_config USING btree (active) WHERE active;
COMMENT ON INDEX public.ux_llm_provider_config_active IS '唯一索引：保证限定范围内不重复；USING btree (active) WHERE active';

-- 串行分配 LLM 连接配置版本的单例计数器。
CREATE TABLE public.llm_provider_config_counter (
    id smallint NOT NULL, -- 记录身份
    current_version integer NOT NULL, -- 当前配置版本或单例配置版本计数
    CONSTRAINT llm_provider_config_counter_current_version_check CHECK ((current_version >= 0)),
    CONSTRAINT llm_provider_config_counter_id_check CHECK ((id = 1)),
    CONSTRAINT llm_provider_config_counter_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE public.llm_provider_config_counter IS '串行分配 LLM 连接配置版本的单例计数器';
COMMENT ON COLUMN public.llm_provider_config_counter.id IS '记录身份';
COMMENT ON COLUMN public.llm_provider_config_counter.current_version IS '当前配置版本或单例配置版本计数';
COMMENT ON CONSTRAINT llm_provider_config_counter_current_version_check ON public.llm_provider_config_counter IS '数据有效性约束：CHECK ((current_version >= 0))';
COMMENT ON CONSTRAINT llm_provider_config_counter_id_check ON public.llm_provider_config_counter IS '数据有效性约束：CHECK ((id = 1))';
COMMENT ON CONSTRAINT llm_provider_config_counter_pkey ON public.llm_provider_config_counter IS '主键：唯一标识串行分配 LLM 连接配置版本的单例计数器的记录  (id)';
COMMENT ON INDEX public.llm_provider_config_counter_pkey IS '支撑主键 llm_provider_config_counter.llm_provider_config_counter_pkey';

-- 管理员媒体连接身份、平台和当前不可变连接版本。
CREATE TABLE public.media_provider_connection (
    id uuid NOT NULL, -- 记录身份
    name character varying(160) NOT NULL, -- 显示名称
    enabled boolean NOT NULL, -- 目录条目是否允许创建新任务
    version bigint NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    current_version integer NOT NULL, -- 当前配置版本或单例配置版本计数
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    platform character varying(24) DEFAULT 'MOCK'::character varying NOT NULL, -- 媒体连接平台类型
    CONSTRAINT ck_media_connection_platform CHECK ((platform IN ('LOCAL', 'MOCK', 'COMFYUI', 'OPENAI', 'GOOGLE', 'ARK', 'VOLCENGINE', 'AUTODL', 'RUNNINGHUB'))),
    CONSTRAINT media_provider_connection_current_version_check CHECK ((current_version > 0)),
    CONSTRAINT media_provider_connection_name_check CHECK ((length(btrim((name)::text)) > 0)),
    CONSTRAINT media_provider_connection_version_check CHECK ((version >= 0)),
    CONSTRAINT media_provider_connection_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE public.media_provider_connection IS '管理员媒体连接身份、平台和当前不可变连接版本';
COMMENT ON COLUMN public.media_provider_connection.id IS '记录身份';
COMMENT ON COLUMN public.media_provider_connection.name IS '显示名称';
COMMENT ON COLUMN public.media_provider_connection.enabled IS '目录条目是否允许创建新任务';
COMMENT ON COLUMN public.media_provider_connection.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.media_provider_connection.current_version IS '当前配置版本或单例配置版本计数';
COMMENT ON COLUMN public.media_provider_connection.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.media_provider_connection.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON COLUMN public.media_provider_connection.platform IS '媒体连接平台类型';
COMMENT ON CONSTRAINT ck_media_connection_platform ON public.media_provider_connection IS '数据有效性约束：CHECK ((platform IN (''LOCAL'', ''MOCK'', ''COMFYUI'', ''OPENAI'', ''GOOGLE'', ''ARK'', ''VOLCENGINE'', ''AUTODL'', ''RUNNINGHUB'')))';
COMMENT ON CONSTRAINT media_provider_connection_current_version_check ON public.media_provider_connection IS '数据有效性约束：CHECK ((current_version > 0))';
COMMENT ON CONSTRAINT media_provider_connection_name_check ON public.media_provider_connection IS '数据有效性约束：CHECK ((length(btrim((name)::text)) > 0))';
COMMENT ON CONSTRAINT media_provider_connection_version_check ON public.media_provider_connection IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT media_provider_connection_pkey ON public.media_provider_connection IS '主键：唯一标识管理员媒体连接身份、平台和当前不可变连接版本的记录  (id)';
COMMENT ON INDEX public.media_provider_connection_pkey IS '支撑主键 media_provider_connection.media_provider_connection_pkey';

-- 媒体生成风格目录及内置风格缩略图。
CREATE TABLE public.media_style (
    id uuid NOT NULL, -- 记录身份
    name character varying(80) NOT NULL, -- 显示名称
    category character varying(40) NOT NULL, -- 目录分类
    prompt_suffix text NOT NULL, -- 选用风格附加的提示词
    enabled boolean DEFAULT true NOT NULL, -- 目录条目是否允许创建新任务
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    builtin_key character varying(40), -- 内置风格稳定标识；用户风格为空
    thumbnail_bytes bytea, -- 内置风格缩略图字节
    thumbnail_content_type character varying(32), -- 风格缩略图 MIME 类型
    created_at timestamp with time zone DEFAULT now() NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone DEFAULT now() NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT ck_media_style_category CHECK (((length(TRIM(BOTH FROM category)) >= 1) AND (length(TRIM(BOTH FROM category)) <= 40))),
    CONSTRAINT ck_media_style_name CHECK (((length(TRIM(BOTH FROM name)) >= 1) AND (length(TRIM(BOTH FROM name)) <= 80))),
    CONSTRAINT ck_media_style_preview CHECK ((((thumbnail_bytes IS NULL) AND (thumbnail_content_type IS NULL)) OR ((thumbnail_bytes IS NOT NULL) AND (thumbnail_content_type IS NOT NULL) AND ((octet_length(thumbnail_bytes) >= 1) AND (octet_length(thumbnail_bytes) <= 1048576)) AND ((thumbnail_content_type)::text = 'image/png'::text)))),
    CONSTRAINT ck_media_style_prompt CHECK (((length(TRIM(BOTH FROM prompt_suffix)) >= 1) AND (length(TRIM(BOTH FROM prompt_suffix)) <= 4000))),
    CONSTRAINT ck_media_style_version CHECK ((version >= 0)),
    CONSTRAINT media_style_pkey PRIMARY KEY (id),
    CONSTRAINT uq_media_style_builtin UNIQUE (builtin_key)
);

COMMENT ON TABLE public.media_style IS '媒体生成风格目录及内置风格缩略图';
COMMENT ON COLUMN public.media_style.id IS '记录身份';
COMMENT ON COLUMN public.media_style.name IS '显示名称';
COMMENT ON COLUMN public.media_style.category IS '目录分类';
COMMENT ON COLUMN public.media_style.prompt_suffix IS '选用风格附加的提示词';
COMMENT ON COLUMN public.media_style.enabled IS '目录条目是否允许创建新任务';
COMMENT ON COLUMN public.media_style.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.media_style.builtin_key IS '内置风格稳定标识；用户风格为空';
COMMENT ON COLUMN public.media_style.thumbnail_bytes IS '内置风格缩略图字节';
COMMENT ON COLUMN public.media_style.thumbnail_content_type IS '风格缩略图 MIME 类型';
COMMENT ON COLUMN public.media_style.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.media_style.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT ck_media_style_category ON public.media_style IS '数据有效性约束：CHECK (((length(TRIM(BOTH FROM category)) >= 1) AND (length(TRIM(BOTH FROM category)) <= 40)))';
COMMENT ON CONSTRAINT ck_media_style_name ON public.media_style IS '数据有效性约束：CHECK (((length(TRIM(BOTH FROM name)) >= 1) AND (length(TRIM(BOTH FROM name)) <= 80)))';
COMMENT ON CONSTRAINT ck_media_style_preview ON public.media_style IS '数据有效性约束：CHECK ((((thumbnail_bytes IS NULL) AND (thumbnail_content_type IS NULL)) OR ((thumbnail_bytes IS NOT NULL) AND (thumbnail_content_type IS NOT NULL) AND ((octet_length(thumbnail_bytes) >= 1) AND (octet_length(thumbnail_bytes) <= 1048576)) AND ((thumbnail_content_type)::text = ''image/png''::text))))';
COMMENT ON CONSTRAINT ck_media_style_prompt ON public.media_style IS '数据有效性约束：CHECK (((length(TRIM(BOTH FROM prompt_suffix)) >= 1) AND (length(TRIM(BOTH FROM prompt_suffix)) <= 4000)))';
COMMENT ON CONSTRAINT ck_media_style_version ON public.media_style IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT media_style_pkey ON public.media_style IS '主键：唯一标识媒体生成风格目录及内置风格缩略图的记录  (id)';
COMMENT ON CONSTRAINT uq_media_style_builtin ON public.media_style IS '唯一约束：禁止作用域内重复记录  (builtin_key)';
COMMENT ON INDEX public.media_style_pkey IS '支撑主键 media_style.media_style_pkey';
COMMENT ON INDEX public.uq_media_style_builtin IS '支撑唯一约束 media_style.uq_media_style_builtin';

-- Spring Session JDBC 持久会话及过期索引元数据。
CREATE TABLE public.spring_session (
    primary_id character(36) NOT NULL, -- Spring Session 内部主键
    session_id character(36) NOT NULL, -- 客户端会话标识
    creation_time bigint NOT NULL, -- 会话创建时间，单位 Unix 毫秒
    last_access_time bigint NOT NULL, -- 会话最后访问时间，单位 Unix 毫秒
    max_inactive_interval integer NOT NULL, -- 会话最大空闲间隔，单位秒
    expiry_time bigint NOT NULL, -- 会话过期时间，单位 Unix 毫秒
    principal_name character varying(100), -- 已登录会话的主体名称
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

COMMENT ON TABLE public.spring_session IS 'Spring Session JDBC 持久会话及过期索引元数据';
COMMENT ON COLUMN public.spring_session.primary_id IS 'Spring Session 内部主键';
COMMENT ON COLUMN public.spring_session.session_id IS '客户端会话标识';
COMMENT ON COLUMN public.spring_session.creation_time IS '会话创建时间，单位 Unix 毫秒';
COMMENT ON COLUMN public.spring_session.last_access_time IS '会话最后访问时间，单位 Unix 毫秒';
COMMENT ON COLUMN public.spring_session.max_inactive_interval IS '会话最大空闲间隔，单位秒';
COMMENT ON COLUMN public.spring_session.expiry_time IS '会话过期时间，单位 Unix 毫秒';
COMMENT ON COLUMN public.spring_session.principal_name IS '已登录会话的主体名称';
COMMENT ON CONSTRAINT spring_session_pk ON public.spring_session IS '主键：唯一标识Spring Session JDBC 持久会话及过期索引元数据的记录  (primary_id)';
COMMENT ON INDEX public.spring_session_pk IS '支撑主键 spring_session.spring_session_pk';

CREATE UNIQUE INDEX spring_session_ix1 ON public.spring_session USING btree (session_id);
COMMENT ON INDEX public.spring_session_ix1 IS '唯一索引：保证限定范围内不重复；USING btree (session_id)';

CREATE INDEX spring_session_ix2 ON public.spring_session USING btree (expiry_time);
COMMENT ON INDEX public.spring_session_ix2 IS '查询索引：支持Spring Session JDBC 持久会话及过期索引元数据的定位与排序；USING btree (expiry_time)';

CREATE INDEX spring_session_ix3 ON public.spring_session USING btree (principal_name);
COMMENT ON INDEX public.spring_session_ix3 IS '查询索引：支持Spring Session JDBC 持久会话及过期索引元数据的定位与排序；USING btree (principal_name)';

-- 不可变本地或对象存储配置及加密凭据。
CREATE TABLE public.storage_profile (
    id uuid NOT NULL, -- 记录身份
    name character varying(120) NOT NULL, -- 显示名称
    provider character varying(20) NOT NULL, -- Provider 或存储实现类型
    endpoint character varying(512) NOT NULL, -- 管理员配置的连接地址
    region character varying(80) NOT NULL, -- 对象存储区域
    bucket character varying(63) NOT NULL, -- 对象存储桶
    key_prefix character varying(120) NOT NULL, -- 对象存储相对键前缀
    path_style boolean NOT NULL, -- 是否使用对象存储路径式访问
    credential_version integer NOT NULL, -- 连接凭据版本
    credential_ciphertext bytea NOT NULL, -- 服务端加密凭据密文
    credential_nonce bytea NOT NULL, -- 凭据加密使用的 nonce
    credential_key_version integer NOT NULL, -- 凭据加密主密钥版本
    access_key_mask character varying(16) NOT NULL, -- 对象存储访问密钥掩码
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT storage_profile_credential_key_version_check CHECK ((credential_key_version > 0)),
    CONSTRAINT storage_profile_credential_nonce_check CHECK ((octet_length(credential_nonce) = 12)),
    CONSTRAINT storage_profile_credential_version_check CHECK ((credential_version > 0)),
    CONSTRAINT storage_profile_provider_check CHECK ((provider IN ('ALIYUN_OSS', 'TENCENT_COS', 'S3'))),
    CONSTRAINT storage_profile_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE public.storage_profile IS '不可变本地或对象存储配置及加密凭据';
COMMENT ON COLUMN public.storage_profile.id IS '记录身份';
COMMENT ON COLUMN public.storage_profile.name IS '显示名称';
COMMENT ON COLUMN public.storage_profile.provider IS 'Provider 或存储实现类型';
COMMENT ON COLUMN public.storage_profile.endpoint IS '管理员配置的连接地址';
COMMENT ON COLUMN public.storage_profile.region IS '对象存储区域';
COMMENT ON COLUMN public.storage_profile.bucket IS '对象存储桶';
COMMENT ON COLUMN public.storage_profile.key_prefix IS '对象存储相对键前缀';
COMMENT ON COLUMN public.storage_profile.path_style IS '是否使用对象存储路径式访问';
COMMENT ON COLUMN public.storage_profile.credential_version IS '连接凭据版本';
COMMENT ON COLUMN public.storage_profile.credential_ciphertext IS '服务端加密凭据密文';
COMMENT ON COLUMN public.storage_profile.credential_nonce IS '凭据加密使用的 nonce';
COMMENT ON COLUMN public.storage_profile.credential_key_version IS '凭据加密主密钥版本';
COMMENT ON COLUMN public.storage_profile.access_key_mask IS '对象存储访问密钥掩码';
COMMENT ON COLUMN public.storage_profile.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT storage_profile_credential_key_version_check ON public.storage_profile IS '数据有效性约束：CHECK ((credential_key_version > 0))';
COMMENT ON CONSTRAINT storage_profile_credential_nonce_check ON public.storage_profile IS '数据有效性约束：CHECK ((octet_length(credential_nonce) = 12))';
COMMENT ON CONSTRAINT storage_profile_credential_version_check ON public.storage_profile IS '数据有效性约束：CHECK ((credential_version > 0))';
COMMENT ON CONSTRAINT storage_profile_provider_check ON public.storage_profile IS '数据有效性约束：CHECK ((provider IN (''ALIYUN_OSS'', ''TENCENT_COS'', ''S3'')))';
COMMENT ON CONSTRAINT storage_profile_pkey ON public.storage_profile IS '主键：唯一标识不可变本地或对象存储配置及加密凭据的记录  (id)';
COMMENT ON INDEX public.storage_profile_pkey IS '支撑主键 storage_profile.storage_profile_pkey';

-- 用户创作 Skill 身份、当前发布版本和回收站状态。
CREATE TABLE public.creative_skill (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    title character varying(160) NOT NULL, -- 显示标题
    description character varying(1024) DEFAULT ''::character varying NOT NULL, -- 用户可见说明
    current_version_id uuid, -- 当前发布版本指针；应用在同一事务插入所属版本并执行目录 CAS，不设循环外键
    trashed_at timestamp with time zone, -- 移入回收站的时间；未删除时为空
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT creative_skill_title_check CHECK ((length(btrim((title)::text)) > 0)),
    CONSTRAINT creative_skill_version_check CHECK ((version >= 0)),
    CONSTRAINT creative_skill_pkey PRIMARY KEY (id),
    CONSTRAINT creative_skill_owner_id_id_key UNIQUE (owner_id, id),
    CONSTRAINT creative_skill_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id)
);

COMMENT ON TABLE public.creative_skill IS '用户创作 Skill 身份、当前发布版本和回收站状态';
COMMENT ON COLUMN public.creative_skill.id IS '记录身份';
COMMENT ON COLUMN public.creative_skill.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.creative_skill.title IS '显示标题';
COMMENT ON COLUMN public.creative_skill.description IS '用户可见说明';
COMMENT ON COLUMN public.creative_skill.current_version_id IS '当前发布版本指针；应用在同一事务插入所属版本并执行目录 CAS，不设循环外键';
COMMENT ON COLUMN public.creative_skill.trashed_at IS '移入回收站的时间；未删除时为空';
COMMENT ON COLUMN public.creative_skill.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.creative_skill.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.creative_skill.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT creative_skill_title_check ON public.creative_skill IS '数据有效性约束：CHECK ((length(btrim((title)::text)) > 0))';
COMMENT ON CONSTRAINT creative_skill_version_check ON public.creative_skill IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT creative_skill_owner_id_id_key ON public.creative_skill IS '唯一约束：禁止作用域内重复记录  (owner_id, id)';
COMMENT ON CONSTRAINT creative_skill_pkey ON public.creative_skill IS '主键：唯一标识用户创作 Skill 身份、当前发布版本和回收站状态的记录  (id)';
COMMENT ON CONSTRAINT creative_skill_owner_id_fkey ON public.creative_skill IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON INDEX public.creative_skill_pkey IS '支撑主键 creative_skill.creative_skill_pkey';
COMMENT ON INDEX public.creative_skill_owner_id_id_key IS '支撑唯一约束 creative_skill.creative_skill_owner_id_id_key';

CREATE INDEX ix_creative_skill_list ON public.creative_skill USING btree (owner_id, trashed_at, updated_at DESC, id DESC);
COMMENT ON INDEX public.ix_creative_skill_list IS '查询索引：支持用户创作 Skill 身份、当前发布版本和回收站状态的定位与排序；USING btree (owner_id, trashed_at, updated_at DESC, id DESC)';

-- 按可信身份和作用域记录命令摘要与响应，拒绝同键不同载荷。
CREATE TABLE public.idempotency_record (
    principal_id uuid NOT NULL, -- 服务端可信请求身份
    scope character varying(120) NOT NULL, -- 命令或共享范围
    idempotency_key character varying(200) NOT NULL, -- 作用域内的幂等命令键
    request_hash character(64) NOT NULL, -- 规范化请求载荷 SHA-256 摘要
    state character varying(24) NOT NULL, -- 幂等请求处理状态
    resource_id uuid, -- 幂等命令创建或修改的资源身份
    response_json jsonb, -- 已提交的命令响应或完整模型响应
    expires_at timestamp with time zone NOT NULL, -- 记录或审批过期时间
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT ck_idempotency_completion CHECK (((((state)::text = 'IN_PROGRESS'::text) AND (resource_id IS NULL) AND (response_json IS NULL)) OR (((state)::text = 'COMPLETED'::text) AND (resource_id IS NOT NULL) AND (response_json IS NOT NULL)))),
    CONSTRAINT ck_idempotency_key_not_blank CHECK ((length(btrim((idempotency_key)::text)) > 0)),
    CONSTRAINT ck_idempotency_request_hash CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_idempotency_state CHECK ((state IN ('IN_PROGRESS', 'COMPLETED'))),
    CONSTRAINT idempotency_record_pkey PRIMARY KEY (principal_id, scope, idempotency_key),
    CONSTRAINT fk_idempotency_principal FOREIGN KEY (principal_id) REFERENCES public.app_user(id)
);

COMMENT ON TABLE public.idempotency_record IS '按可信身份和作用域记录命令摘要与响应，拒绝同键不同载荷';
COMMENT ON COLUMN public.idempotency_record.principal_id IS '服务端可信请求身份';
COMMENT ON COLUMN public.idempotency_record.scope IS '命令或共享范围';
COMMENT ON COLUMN public.idempotency_record.idempotency_key IS '作用域内的幂等命令键';
COMMENT ON COLUMN public.idempotency_record.request_hash IS '规范化请求载荷 SHA-256 摘要';
COMMENT ON COLUMN public.idempotency_record.state IS '幂等请求处理状态';
COMMENT ON COLUMN public.idempotency_record.resource_id IS '幂等命令创建或修改的资源身份';
COMMENT ON COLUMN public.idempotency_record.response_json IS '已提交的命令响应或完整模型响应';
COMMENT ON COLUMN public.idempotency_record.expires_at IS '记录或审批过期时间';
COMMENT ON COLUMN public.idempotency_record.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.idempotency_record.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT ck_idempotency_completion ON public.idempotency_record IS '数据有效性约束：CHECK (((((state)::text = ''IN_PROGRESS''::text) AND (resource_id IS NULL) AND (response_json IS NULL)) OR (((state)::text = ''COMPLETED''::text) AND (resource_id IS NOT NULL) AND (response_json IS NOT NULL))))';
COMMENT ON CONSTRAINT ck_idempotency_key_not_blank ON public.idempotency_record IS '数据有效性约束：CHECK ((length(btrim((idempotency_key)::text)) > 0))';
COMMENT ON CONSTRAINT ck_idempotency_request_hash ON public.idempotency_record IS '数据有效性约束：CHECK ((request_hash ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT ck_idempotency_state ON public.idempotency_record IS '数据有效性约束：CHECK ((state IN (''IN_PROGRESS'', ''COMPLETED'')))';
COMMENT ON CONSTRAINT idempotency_record_pkey ON public.idempotency_record IS '主键：唯一标识按可信身份和作用域记录命令摘要与响应，拒绝同键不同载荷的记录  (principal_id, scope, idempotency_key)';
COMMENT ON CONSTRAINT fk_idempotency_principal ON public.idempotency_record IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (principal_id) REFERENCES public.app_user(id)';
COMMENT ON INDEX public.idempotency_record_pkey IS '支撑主键 idempotency_record.idempotency_record_pkey';

CREATE INDEX ix_idempotency_expiry ON public.idempotency_record USING btree (expires_at);
COMMENT ON INDEX public.ix_idempotency_expiry IS '查询索引：支持按可信身份和作用域记录命令摘要与响应，拒绝同键不同载荷的定位与排序；USING btree (expires_at)';

-- 个人素材库待清理的字节引用及下次清理时间。
CREATE TABLE public.library_cleanup (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    metadata_json jsonb NOT NULL, -- 存储对象元数据，包含定位和完整性验证所需信息
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL, -- 字节清理下次允许尝试的时间
    CONSTRAINT library_cleanup_metadata_json_check CHECK ((jsonb_typeof(metadata_json) = 'object'::text)),
    CONSTRAINT library_cleanup_schema CHECK (((metadata_json ? 'schemaVersion'::text) AND ((metadata_json ->> 'schemaVersion'::text) = '1'::text))),
    CONSTRAINT library_cleanup_pkey PRIMARY KEY (id),
    CONSTRAINT library_cleanup_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id)
);

COMMENT ON TABLE public.library_cleanup IS '个人素材库待清理的字节引用及下次清理时间';
COMMENT ON COLUMN public.library_cleanup.id IS '记录身份';
COMMENT ON COLUMN public.library_cleanup.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.library_cleanup.metadata_json IS '存储对象元数据，包含定位和完整性验证所需信息';
COMMENT ON COLUMN public.library_cleanup.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.library_cleanup.next_attempt_at IS '字节清理下次允许尝试的时间';
COMMENT ON CONSTRAINT library_cleanup_metadata_json_check ON public.library_cleanup IS '数据有效性约束：CHECK ((jsonb_typeof(metadata_json) = ''object''::text))';
COMMENT ON CONSTRAINT library_cleanup_schema ON public.library_cleanup IS '数据有效性约束：CHECK (((metadata_json ? ''schemaVersion''::text) AND ((metadata_json ->> ''schemaVersion''::text) = ''1''::text)))';
COMMENT ON CONSTRAINT library_cleanup_pkey ON public.library_cleanup IS '主键：唯一标识个人素材库待清理的字节引用及下次清理时间的记录  (id)';
COMMENT ON CONSTRAINT library_cleanup_owner_id_fkey ON public.library_cleanup IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON INDEX public.library_cleanup_pkey IS '支撑主键 library_cleanup.library_cleanup_pkey';

CREATE INDEX library_cleanup_due_idx ON public.library_cleanup USING btree (next_attempt_at, created_at, id);
COMMENT ON INDEX public.library_cleanup_due_idx IS '查询索引：支持个人素材库待清理的字节引用及下次清理时间的定位与排序；USING btree (next_attempt_at, created_at, id)';

-- 素材库命令的持久执行、租约、结果与错误。
CREATE TABLE public.library_command (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    command_key character varying(200) NOT NULL, -- 用户命令幂等键
    payload_hash character(64) NOT NULL, -- 规范化命令载荷 SHA-256 摘要
    kind character varying(16) NOT NULL, -- 业务类型，允许值由 CHECK 约束限定
    input_json jsonb NOT NULL, -- 受理时固定的命令输入
    status character varying(16) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    epoch bigint DEFAULT 0 NOT NULL, -- 操作租约的 fencing epoch，旧执行者不得回写
    lease_until timestamp with time zone, -- 当前认领租约过期时间
    result_json jsonb, -- 已提交的结构化执行或审批结果
    error_code character varying(80), -- 稳定错误代码，不含堆栈或凭据
    error_detail character varying(500), -- 已脱敏的公开错误说明
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT library_command_check CHECK ((((status)::text = 'ARCHIVING'::text) = (lease_until IS NOT NULL))),
    CONSTRAINT library_command_command_key_check CHECK ((length(btrim((command_key)::text)) > 0)),
    CONSTRAINT library_command_epoch_check CHECK ((epoch >= 0)),
    CONSTRAINT library_command_input_json_check CHECK ((jsonb_typeof(input_json) = 'object'::text)),
    CONSTRAINT library_command_input_schema CHECK (((input_json ? 'schemaVersion'::text) AND ((input_json ->> 'schemaVersion'::text) = '1'::text))),
    CONSTRAINT library_command_kind_check CHECK ((kind IN ('SAVE', 'UPLOAD', 'IMPORT', 'REFERENCE'))),
    CONSTRAINT library_command_payload_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT library_command_status_check CHECK ((status IN ('ACCEPTED', 'ARCHIVING', 'SUCCEEDED', 'FAILED'))),
    CONSTRAINT library_command_pkey PRIMARY KEY (id),
    CONSTRAINT library_command_owner_id_command_key_key UNIQUE (owner_id, command_key),
    CONSTRAINT library_command_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id)
);

COMMENT ON TABLE public.library_command IS '素材库命令的持久执行、租约、结果与错误';
COMMENT ON COLUMN public.library_command.id IS '记录身份';
COMMENT ON COLUMN public.library_command.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.library_command.command_key IS '用户命令幂等键';
COMMENT ON COLUMN public.library_command.payload_hash IS '规范化命令载荷 SHA-256 摘要';
COMMENT ON COLUMN public.library_command.kind IS '业务类型，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.library_command.input_json IS '受理时固定的命令输入';
COMMENT ON COLUMN public.library_command.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.library_command.epoch IS '操作租约的 fencing epoch，旧执行者不得回写';
COMMENT ON COLUMN public.library_command.lease_until IS '当前认领租约过期时间';
COMMENT ON COLUMN public.library_command.result_json IS '已提交的结构化执行或审批结果';
COMMENT ON COLUMN public.library_command.error_code IS '稳定错误代码，不含堆栈或凭据';
COMMENT ON COLUMN public.library_command.error_detail IS '已脱敏的公开错误说明';
COMMENT ON COLUMN public.library_command.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.library_command.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT library_command_check ON public.library_command IS '数据有效性约束：CHECK ((((status)::text = ''ARCHIVING''::text) = (lease_until IS NOT NULL)))';
COMMENT ON CONSTRAINT library_command_command_key_check ON public.library_command IS '数据有效性约束：CHECK ((length(btrim((command_key)::text)) > 0))';
COMMENT ON CONSTRAINT library_command_epoch_check ON public.library_command IS '数据有效性约束：CHECK ((epoch >= 0))';
COMMENT ON CONSTRAINT library_command_input_json_check ON public.library_command IS '数据有效性约束：CHECK ((jsonb_typeof(input_json) = ''object''::text))';
COMMENT ON CONSTRAINT library_command_input_schema ON public.library_command IS '数据有效性约束：CHECK (((input_json ? ''schemaVersion''::text) AND ((input_json ->> ''schemaVersion''::text) = ''1''::text)))';
COMMENT ON CONSTRAINT library_command_kind_check ON public.library_command IS '数据有效性约束：CHECK ((kind IN (''SAVE'', ''UPLOAD'', ''IMPORT'', ''REFERENCE'')))';
COMMENT ON CONSTRAINT library_command_payload_hash_check ON public.library_command IS '数据有效性约束：CHECK ((payload_hash ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT library_command_status_check ON public.library_command IS '数据有效性约束：CHECK ((status IN (''ACCEPTED'', ''ARCHIVING'', ''SUCCEEDED'', ''FAILED'')))';
COMMENT ON CONSTRAINT library_command_owner_id_command_key_key ON public.library_command IS '唯一约束：禁止作用域内重复记录  (owner_id, command_key)';
COMMENT ON CONSTRAINT library_command_pkey ON public.library_command IS '主键：唯一标识素材库命令的持久执行、租约、结果与错误的记录  (id)';
COMMENT ON CONSTRAINT library_command_owner_id_fkey ON public.library_command IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON INDEX public.library_command_pkey IS '支撑主键 library_command.library_command_pkey';
COMMENT ON INDEX public.library_command_owner_id_command_key_key IS '支撑唯一约束 library_command.library_command_owner_id_command_key_key';

CREATE INDEX ix_library_command_claim ON public.library_command USING btree (status, lease_until, created_at);
COMMENT ON INDEX public.ix_library_command_claim IS '查询索引：支持素材库命令的持久执行、租约、结果与错误的定位与排序；USING btree (status, lease_until, created_at)';

-- 个人素材库文件的存储元数据。
CREATE TABLE public.library_file (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    kind character varying(16) NOT NULL, -- 业务类型，允许值由 CHECK 约束限定
    metadata_json jsonb NOT NULL, -- 存储对象元数据，包含定位和完整性验证所需信息
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT library_file_identity CHECK (((metadata_json ?& ARRAY['schemaVersion'::text, 'id'::text, 'ownerId'::text, 'kind'::text, 'objectKey'::text, 'contentType'::text, 'byteSize'::text, 'sha256'::text]) AND ((metadata_json ->> 'id'::text) = (id)::text) AND ((metadata_json ->> 'ownerId'::text) = (owner_id)::text) AND ((metadata_json ->> 'kind'::text) = (kind)::text) AND (((metadata_json ->> 'byteSize'::text))::bigint > 0) AND ((metadata_json ->> 'sha256'::text) ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT library_file_kind_check CHECK ((kind IN ('IMAGE', 'VIDEO', 'AUDIO'))),
    CONSTRAINT library_file_metadata_json_check CHECK (((jsonb_typeof(metadata_json) = 'object'::text) AND ((metadata_json ->> 'schemaVersion'::text) = '1'::text))),
    CONSTRAINT library_file_pkey PRIMARY KEY (id),
    CONSTRAINT library_file_owner_id_id_key UNIQUE (owner_id, id),
    CONSTRAINT library_file_owner_id_id_kind_key UNIQUE (owner_id, id, kind),
    CONSTRAINT library_file_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id)
);

COMMENT ON TABLE public.library_file IS '个人素材库文件的存储元数据';
COMMENT ON COLUMN public.library_file.id IS '记录身份';
COMMENT ON COLUMN public.library_file.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.library_file.kind IS '业务类型，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.library_file.metadata_json IS '存储对象元数据，包含定位和完整性验证所需信息';
COMMENT ON COLUMN public.library_file.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT library_file_identity ON public.library_file IS '数据有效性约束：CHECK (((metadata_json ?& ARRAY[''schemaVersion''::text, ''id''::text, ''ownerId''::text, ''kind''::text, ''objectKey''::text, ''contentType''::text, ''byteSize''::text, ''sha256''::text]) AND ((metadata_json ->> ''id''::text) = (id)::text) AND ((metadata_json ->> ''ownerId''::text) = (owner_id)::text) AND ((metadata_json ->> ''kind''::text) = (kind)::text) AND (((metadata_json ->> ''byteSize''::text))::bigint > 0) AND ((metadata_json ->> ''sha256''::text) ~ ''^[0-9a-f]{64}$''::text)))';
COMMENT ON CONSTRAINT library_file_kind_check ON public.library_file IS '数据有效性约束：CHECK ((kind IN (''IMAGE'', ''VIDEO'', ''AUDIO'')))';
COMMENT ON CONSTRAINT library_file_metadata_json_check ON public.library_file IS '数据有效性约束：CHECK (((jsonb_typeof(metadata_json) = ''object''::text) AND ((metadata_json ->> ''schemaVersion''::text) = ''1''::text)))';
COMMENT ON CONSTRAINT library_file_owner_id_id_key ON public.library_file IS '唯一约束：禁止作用域内重复记录  (owner_id, id)';
COMMENT ON CONSTRAINT library_file_owner_id_id_kind_key ON public.library_file IS '唯一约束：禁止作用域内重复记录  (owner_id, id, kind)';
COMMENT ON CONSTRAINT library_file_pkey ON public.library_file IS '主键：唯一标识个人素材库文件的存储元数据的记录  (id)';
COMMENT ON CONSTRAINT library_file_owner_id_fkey ON public.library_file IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON INDEX public.library_file_pkey IS '支撑主键 library_file.library_file_pkey';
COMMENT ON INDEX public.library_file_owner_id_id_key IS '支撑唯一约束 library_file.library_file_owner_id_id_key';
COMMENT ON INDEX public.library_file_owner_id_id_kind_key IS '支撑唯一约束 library_file.library_file_owner_id_id_kind_key';

-- 管理员媒体能力身份、可用状态和当前不可变版本。
CREATE TABLE public.media_capability (
    id uuid NOT NULL, -- 记录身份
    connection_id uuid NOT NULL, -- 不可变媒体连接所属身份或输入来源连线身份
    name character varying(160) NOT NULL, -- 显示名称
    enabled boolean NOT NULL, -- 目录条目是否允许创建新任务
    version bigint NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    current_version integer NOT NULL, -- 当前配置版本或单例配置版本计数
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT media_capability_current_version_check CHECK ((current_version > 0)),
    CONSTRAINT media_capability_name_check CHECK ((length(btrim((name)::text)) > 0)),
    CONSTRAINT media_capability_version_check CHECK ((version >= 0)),
    CONSTRAINT media_capability_pkey PRIMARY KEY (id),
    CONSTRAINT uq_media_capability_connection_id UNIQUE (connection_id, id),
    CONSTRAINT media_capability_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES public.media_provider_connection(id)
);

COMMENT ON TABLE public.media_capability IS '管理员媒体能力身份、可用状态和当前不可变版本';
COMMENT ON COLUMN public.media_capability.id IS '记录身份';
COMMENT ON COLUMN public.media_capability.connection_id IS '不可变媒体连接所属身份或输入来源连线身份';
COMMENT ON COLUMN public.media_capability.name IS '显示名称';
COMMENT ON COLUMN public.media_capability.enabled IS '目录条目是否允许创建新任务';
COMMENT ON COLUMN public.media_capability.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.media_capability.current_version IS '当前配置版本或单例配置版本计数';
COMMENT ON COLUMN public.media_capability.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.media_capability.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT media_capability_current_version_check ON public.media_capability IS '数据有效性约束：CHECK ((current_version > 0))';
COMMENT ON CONSTRAINT media_capability_name_check ON public.media_capability IS '数据有效性约束：CHECK ((length(btrim((name)::text)) > 0))';
COMMENT ON CONSTRAINT media_capability_version_check ON public.media_capability IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT media_capability_pkey ON public.media_capability IS '主键：唯一标识管理员媒体能力身份、可用状态和当前不可变版本的记录  (id)';
COMMENT ON CONSTRAINT uq_media_capability_connection_id ON public.media_capability IS '唯一约束：禁止作用域内重复记录  (connection_id, id)';
COMMENT ON CONSTRAINT media_capability_connection_id_fkey ON public.media_capability IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (connection_id) REFERENCES public.media_provider_connection(id)';
COMMENT ON INDEX public.media_capability_pkey IS '支撑主键 media_capability.media_capability_pkey';
COMMENT ON INDEX public.uq_media_capability_connection_id IS '支撑唯一约束 media_capability.uq_media_capability_connection_id';

-- 媒体连接创建命令的幂等键与载荷摘要。
CREATE TABLE public.media_connection_create_key (
    idempotency_key character varying(160) NOT NULL, -- 作用域内的幂等命令键
    payload_sha256 character(64) NOT NULL, -- 规范化创建请求 SHA-256 摘要
    connection_id uuid NOT NULL, -- 不可变媒体连接所属身份或输入来源连线身份
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT media_connection_create_key_payload_sha256_check CHECK ((payload_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT media_connection_create_key_pkey PRIMARY KEY (idempotency_key),
    CONSTRAINT media_connection_create_key_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES public.media_provider_connection(id) DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON TABLE public.media_connection_create_key IS '媒体连接创建命令的幂等键与载荷摘要';
COMMENT ON COLUMN public.media_connection_create_key.idempotency_key IS '作用域内的幂等命令键';
COMMENT ON COLUMN public.media_connection_create_key.payload_sha256 IS '规范化创建请求 SHA-256 摘要';
COMMENT ON COLUMN public.media_connection_create_key.connection_id IS '不可变媒体连接所属身份或输入来源连线身份';
COMMENT ON COLUMN public.media_connection_create_key.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT media_connection_create_key_payload_sha256_check ON public.media_connection_create_key IS '数据有效性约束：CHECK ((payload_sha256 ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT media_connection_create_key_pkey ON public.media_connection_create_key IS '主键：唯一标识媒体连接创建命令的幂等键与载荷摘要的记录  (idempotency_key)';
COMMENT ON CONSTRAINT media_connection_create_key_connection_id_fkey ON public.media_connection_create_key IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (connection_id) REFERENCES public.media_provider_connection(id) DEFERRABLE INITIALLY DEFERRED';
COMMENT ON INDEX public.media_connection_create_key_pkey IS '支撑主键 media_connection_create_key.media_connection_create_key_pkey';

-- 不可变媒体连接地址、精确来源摘要与加密凭据。
CREATE TABLE public.media_provider_connection_version (
    connection_id uuid NOT NULL, -- 不可变媒体连接所属身份或输入来源连线身份
    version integer NOT NULL, -- 连接内部的不可变配置版本
    origin character varying(500), -- 管理员固定的 Provider 来源地址；内置 Mock/LOCAL 连接为空
    origin_sha256 character(64), -- 精确 Provider 来源地址 SHA-256 摘要，用于来源漂移检查
    credential_ciphertext bytea, -- 服务端加密凭据密文
    credential_nonce bytea, -- 凭据加密使用的 nonce
    credential_key_version integer, -- 凭据加密主密钥版本
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    key_mask character varying(24), -- 供设置页显示的凭据掩码
    CONSTRAINT ck_media_connection_credential CHECK ((((credential_ciphertext IS NULL) AND (credential_nonce IS NULL) AND (credential_key_version IS NULL)) OR ((credential_ciphertext IS NOT NULL) AND (credential_nonce IS NOT NULL) AND (credential_key_version > 0)))),
    CONSTRAINT ck_media_connection_origin_hash CHECK (((origin_sha256 IS NULL) OR (origin_sha256 ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT media_provider_connection_version_version_check CHECK ((version > 0)),
    CONSTRAINT media_provider_connection_version_pkey PRIMARY KEY (connection_id, version),
    CONSTRAINT media_provider_connection_version_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES public.media_provider_connection(id)
);

COMMENT ON TABLE public.media_provider_connection_version IS '不可变媒体连接地址、精确来源摘要与加密凭据';
COMMENT ON COLUMN public.media_provider_connection_version.connection_id IS '不可变媒体连接所属身份或输入来源连线身份';
COMMENT ON COLUMN public.media_provider_connection_version.version IS '连接内部的不可变配置版本';
COMMENT ON COLUMN public.media_provider_connection_version.origin IS '管理员固定的 Provider 来源地址；内置 Mock/LOCAL 连接为空';
COMMENT ON COLUMN public.media_provider_connection_version.origin_sha256 IS '精确 Provider 来源地址 SHA-256 摘要，用于来源漂移检查';
COMMENT ON COLUMN public.media_provider_connection_version.credential_ciphertext IS '服务端加密凭据密文';
COMMENT ON COLUMN public.media_provider_connection_version.credential_nonce IS '凭据加密使用的 nonce';
COMMENT ON COLUMN public.media_provider_connection_version.credential_key_version IS '凭据加密主密钥版本';
COMMENT ON COLUMN public.media_provider_connection_version.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.media_provider_connection_version.key_mask IS '供设置页显示的凭据掩码';
COMMENT ON CONSTRAINT ck_media_connection_credential ON public.media_provider_connection_version IS '数据有效性约束：CHECK ((((credential_ciphertext IS NULL) AND (credential_nonce IS NULL) AND (credential_key_version IS NULL)) OR ((credential_ciphertext IS NOT NULL) AND (credential_nonce IS NOT NULL) AND (credential_key_version > 0))))';
COMMENT ON CONSTRAINT ck_media_connection_origin_hash ON public.media_provider_connection_version IS '数据有效性约束：CHECK (((origin_sha256 IS NULL) OR (origin_sha256 ~ ''^[0-9a-f]{64}$''::text)))';
COMMENT ON CONSTRAINT media_provider_connection_version_version_check ON public.media_provider_connection_version IS '数据有效性约束：CHECK ((version > 0))';
COMMENT ON CONSTRAINT media_provider_connection_version_pkey ON public.media_provider_connection_version IS '主键：唯一标识不可变媒体连接地址、精确来源摘要与加密凭据的记录  (connection_id, version)';
COMMENT ON CONSTRAINT media_provider_connection_version_connection_id_fkey ON public.media_provider_connection_version IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (connection_id) REFERENCES public.media_provider_connection(id)';
COMMENT ON INDEX public.media_provider_connection_version_pkey IS '支撑主键 media_provider_connection_version.media_provider_connection_version_pkey';

-- 用户或系统媒体模板及提示词。
CREATE TABLE public.media_template (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    scope character varying(16) NOT NULL, -- 命令或共享范围
    target_kind character varying(16) NOT NULL, -- 模板适用的媒体操作类型
    name character varying(160) NOT NULL, -- 显示名称
    prompt character varying(20000) NOT NULL, -- 用户媒体生成提示词
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT media_template_name_check CHECK ((length(btrim((name)::text)) > 0)),
    CONSTRAINT media_template_prompt_check CHECK ((length(btrim((prompt)::text)) > 0)),
    CONSTRAINT media_template_scope_check CHECK ((scope IN ('PERSONAL', 'SYSTEM'))),
    CONSTRAINT media_template_target_kind_check CHECK ((target_kind IN ('IMAGE', 'VIDEO'))),
    CONSTRAINT media_template_version_check CHECK ((version >= 0)),
    CONSTRAINT media_template_pkey PRIMARY KEY (id),
    CONSTRAINT media_template_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id)
);

COMMENT ON TABLE public.media_template IS '用户或系统媒体模板及提示词';
COMMENT ON COLUMN public.media_template.id IS '记录身份';
COMMENT ON COLUMN public.media_template.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.media_template.scope IS '命令或共享范围';
COMMENT ON COLUMN public.media_template.target_kind IS '模板适用的媒体操作类型';
COMMENT ON COLUMN public.media_template.name IS '显示名称';
COMMENT ON COLUMN public.media_template.prompt IS '用户媒体生成提示词';
COMMENT ON COLUMN public.media_template.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.media_template.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.media_template.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT media_template_name_check ON public.media_template IS '数据有效性约束：CHECK ((length(btrim((name)::text)) > 0))';
COMMENT ON CONSTRAINT media_template_prompt_check ON public.media_template IS '数据有效性约束：CHECK ((length(btrim((prompt)::text)) > 0))';
COMMENT ON CONSTRAINT media_template_scope_check ON public.media_template IS '数据有效性约束：CHECK ((scope IN (''PERSONAL'', ''SYSTEM'')))';
COMMENT ON CONSTRAINT media_template_target_kind_check ON public.media_template IS '数据有效性约束：CHECK ((target_kind IN (''IMAGE'', ''VIDEO'')))';
COMMENT ON CONSTRAINT media_template_version_check ON public.media_template IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT media_template_pkey ON public.media_template IS '主键：唯一标识用户或系统媒体模板及提示词的记录  (id)';
COMMENT ON CONSTRAINT media_template_owner_id_fkey ON public.media_template IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON INDEX public.media_template_pkey IS '支撑主键 media_template.media_template_pkey';

CREATE INDEX ix_media_template_list ON public.media_template USING btree (scope, owner_id, target_kind, updated_at DESC);
COMMENT ON INDEX public.ix_media_template_list IS '查询索引：支持用户或系统媒体模板及提示词的定位与排序；USING btree (scope, owner_id, target_kind, updated_at DESC)';

-- 模板参考图片的归档存储元数据。
CREATE TABLE public.media_template_image (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    metadata_json jsonb NOT NULL, -- 存储对象元数据，包含定位和完整性验证所需信息
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT media_template_image_metadata_json_check CHECK (((jsonb_typeof(metadata_json) = 'object'::text) AND ((metadata_json ->> 'schemaVersion'::text) = '1'::text) AND ((metadata_json ->> 'kind'::text) = 'IMAGE'::text))),
    CONSTRAINT media_template_image_pkey PRIMARY KEY (id),
    CONSTRAINT media_template_image_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id)
);

COMMENT ON TABLE public.media_template_image IS '模板参考图片的归档存储元数据';
COMMENT ON COLUMN public.media_template_image.id IS '记录身份';
COMMENT ON COLUMN public.media_template_image.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.media_template_image.metadata_json IS '存储对象元数据，包含定位和完整性验证所需信息';
COMMENT ON COLUMN public.media_template_image.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT media_template_image_metadata_json_check ON public.media_template_image IS '数据有效性约束：CHECK (((jsonb_typeof(metadata_json) = ''object''::text) AND ((metadata_json ->> ''schemaVersion''::text) = ''1''::text) AND ((metadata_json ->> ''kind''::text) = ''IMAGE''::text)))';
COMMENT ON CONSTRAINT media_template_image_pkey ON public.media_template_image IS '主键：唯一标识模板参考图片的归档存储元数据的记录  (id)';
COMMENT ON CONSTRAINT media_template_image_owner_id_fkey ON public.media_template_image IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON INDEX public.media_template_image_pkey IS '支撑主键 media_template_image.media_template_image_pkey';

-- 项目权限边界、当前活动 Run、事件序号与并发控制版本。
CREATE TABLE public.project (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    name character varying(120) NOT NULL, -- 显示名称
    aspect_ratio character varying(32) NOT NULL, -- 项目默认画幅比例
    status character varying(32) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    active_run_id uuid, -- 当前活动 Run 指针；应用在项目锁内创建 Run 并占用或释放槽位，不设循环外键
    event_seq bigint DEFAULT 0 NOT NULL, -- 项目内事务分配的已提交事件序号
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    archived_at timestamp with time zone, -- 归档时间；未归档时为空
    CONSTRAINT ck_project_archive_time CHECK (((((status)::text = 'ACTIVE'::text) AND (archived_at IS NULL)) OR (((status)::text = 'ARCHIVED'::text) AND (archived_at IS NOT NULL)))),
    CONSTRAINT ck_project_aspect_ratio CHECK ((aspect_ratio IN ('LANDSCAPE_16_9', 'PORTRAIT_9_16', 'SQUARE_1_1'))),
    CONSTRAINT ck_project_name_non_blank CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT ck_project_status CHECK ((status IN ('ACTIVE', 'ARCHIVED'))),
    CONSTRAINT project_pkey PRIMARY KEY (id),
    CONSTRAINT project_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id)
);

COMMENT ON TABLE public.project IS '项目权限边界、当前活动 Run、事件序号与并发控制版本';
COMMENT ON COLUMN public.project.id IS '记录身份';
COMMENT ON COLUMN public.project.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.project.name IS '显示名称';
COMMENT ON COLUMN public.project.aspect_ratio IS '项目默认画幅比例';
COMMENT ON COLUMN public.project.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.project.active_run_id IS '当前活动 Run 指针；应用在项目锁内创建 Run 并占用或释放槽位，不设循环外键';
COMMENT ON COLUMN public.project.event_seq IS '项目内事务分配的已提交事件序号';
COMMENT ON COLUMN public.project.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.project.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.project.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON COLUMN public.project.archived_at IS '归档时间；未归档时为空';
COMMENT ON CONSTRAINT ck_project_archive_time ON public.project IS '数据有效性约束：CHECK (((((status)::text = ''ACTIVE''::text) AND (archived_at IS NULL)) OR (((status)::text = ''ARCHIVED''::text) AND (archived_at IS NOT NULL))))';
COMMENT ON CONSTRAINT ck_project_aspect_ratio ON public.project IS '数据有效性约束：CHECK ((aspect_ratio IN (''LANDSCAPE_16_9'', ''PORTRAIT_9_16'', ''SQUARE_1_1'')))';
COMMENT ON CONSTRAINT ck_project_name_non_blank ON public.project IS '数据有效性约束：CHECK ((btrim((name)::text) <> ''''::text))';
COMMENT ON CONSTRAINT ck_project_status ON public.project IS '数据有效性约束：CHECK ((status IN (''ACTIVE'', ''ARCHIVED'')))';
COMMENT ON CONSTRAINT project_pkey ON public.project IS '主键：唯一标识项目权限边界、当前活动 Run、事件序号与并发控制版本的记录  (id)';
COMMENT ON CONSTRAINT project_owner_id_fkey ON public.project IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON INDEX public.project_pkey IS '支撑主键 project.project_pkey';

CREATE INDEX ix_project_owner_created ON public.project USING btree (owner_id, created_at DESC, id DESC);
COMMENT ON INDEX public.ix_project_owner_created IS '查询索引：支持项目权限边界、当前活动 Run、事件序号与并发控制版本的定位与排序；USING btree (owner_id, created_at DESC, id DESC)';

-- Spring Session JDBC 序列化会话属性。
CREATE TABLE public.spring_session_attributes (
    session_primary_id character(36) NOT NULL, -- 所属 Spring Session 内部主键
    attribute_name character varying(200) NOT NULL, -- 会话属性名称
    attribute_bytes bytea NOT NULL, -- 会话属性序列化字节
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES public.spring_session(primary_id) ON DELETE CASCADE
);

COMMENT ON TABLE public.spring_session_attributes IS 'Spring Session JDBC 序列化会话属性';
COMMENT ON COLUMN public.spring_session_attributes.session_primary_id IS '所属 Spring Session 内部主键';
COMMENT ON COLUMN public.spring_session_attributes.attribute_name IS '会话属性名称';
COMMENT ON COLUMN public.spring_session_attributes.attribute_bytes IS '会话属性序列化字节';
COMMENT ON CONSTRAINT spring_session_attributes_pk ON public.spring_session_attributes IS '主键：唯一标识Spring Session JDBC 序列化会话属性的记录  (session_primary_id, attribute_name)';
COMMENT ON CONSTRAINT spring_session_attributes_fk ON public.spring_session_attributes IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (session_primary_id) REFERENCES public.spring_session(primary_id) ON DELETE CASCADE';
COMMENT ON INDEX public.spring_session_attributes_pk IS '支撑主键 spring_session_attributes.spring_session_attributes_pk';

-- 当前存储配置选择及并发控制版本；已有资产保留原路由。
CREATE TABLE public.storage_settings (
    singleton boolean DEFAULT true NOT NULL, -- 全局存储配置的单例键
    version integer DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    active_profile_id uuid, -- 当前写入使用的存储配置；已有资产按原路由读取
    CONSTRAINT storage_settings_singleton_check CHECK (singleton),
    CONSTRAINT storage_settings_version_check CHECK ((version >= 0)),
    CONSTRAINT storage_settings_pkey PRIMARY KEY (singleton),
    CONSTRAINT storage_settings_active_profile_id_fkey FOREIGN KEY (active_profile_id) REFERENCES public.storage_profile(id)
);

COMMENT ON TABLE public.storage_settings IS '当前存储配置选择及并发控制版本；已有资产保留原路由';
COMMENT ON COLUMN public.storage_settings.singleton IS '全局存储配置的单例键';
COMMENT ON COLUMN public.storage_settings.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.storage_settings.active_profile_id IS '当前写入使用的存储配置；已有资产按原路由读取';
COMMENT ON CONSTRAINT storage_settings_singleton_check ON public.storage_settings IS '数据有效性约束：CHECK (singleton)';
COMMENT ON CONSTRAINT storage_settings_version_check ON public.storage_settings IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT storage_settings_pkey ON public.storage_settings IS '主键：唯一标识当前存储配置选择及并发控制版本的记录  (singleton)';
COMMENT ON CONSTRAINT storage_settings_active_profile_id_fkey ON public.storage_settings IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (active_profile_id) REFERENCES public.storage_profile(id)';
COMMENT ON INDEX public.storage_settings_pkey IS '支撑主键 storage_settings.storage_settings_pkey';

-- Agent 卡片配置；请求身份和运行上下文保存在 Run 中。
CREATE TABLE public.agent_instance (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    profile_key character varying(80) NOT NULL, -- 内置 Agent 配置标识
    profile_version integer NOT NULL, -- Agent 配置格式版本
    name character varying(120) NOT NULL, -- 显示名称
    instruction character varying(8000) NOT NULL, -- 用户指令或 Agent 系统指令
    output_group_id uuid NOT NULL, -- Agent 输出卡片的画布分组
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    current_conversation_id uuid, -- 当前会话指针；应用按项目及 Agent 归属校验后切换，不设循环外键
    CONSTRAINT ck_agent_instruction_not_blank CHECK ((length(btrim((instruction)::text)) > 0)),
    CONSTRAINT ck_agent_name_not_blank CHECK ((length(btrim((name)::text)) > 0)),
    CONSTRAINT ck_agent_profile_key_not_blank CHECK ((length(btrim((profile_key)::text)) > 0)),
    CONSTRAINT ck_agent_profile_version_positive CHECK ((profile_version > 0)),
    CONSTRAINT ck_agent_version_non_negative CHECK ((version >= 0)),
    CONSTRAINT agent_instance_pkey PRIMARY KEY (id),
    CONSTRAINT uq_agent_instance_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_agent_instance_project FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.agent_instance IS 'Agent 卡片配置；请求身份和运行上下文保存在 Run 中';
COMMENT ON COLUMN public.agent_instance.id IS '记录身份';
COMMENT ON COLUMN public.agent_instance.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.agent_instance.profile_key IS '内置 Agent 配置标识';
COMMENT ON COLUMN public.agent_instance.profile_version IS 'Agent 配置格式版本';
COMMENT ON COLUMN public.agent_instance.name IS '显示名称';
COMMENT ON COLUMN public.agent_instance.instruction IS '用户指令或 Agent 系统指令';
COMMENT ON COLUMN public.agent_instance.output_group_id IS 'Agent 输出卡片的画布分组';
COMMENT ON COLUMN public.agent_instance.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.agent_instance.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.agent_instance.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON COLUMN public.agent_instance.current_conversation_id IS '当前会话指针；应用按项目及 Agent 归属校验后切换，不设循环外键';
COMMENT ON CONSTRAINT ck_agent_instruction_not_blank ON public.agent_instance IS '数据有效性约束：CHECK ((length(btrim((instruction)::text)) > 0))';
COMMENT ON CONSTRAINT ck_agent_name_not_blank ON public.agent_instance IS '数据有效性约束：CHECK ((length(btrim((name)::text)) > 0))';
COMMENT ON CONSTRAINT ck_agent_profile_key_not_blank ON public.agent_instance IS '数据有效性约束：CHECK ((length(btrim((profile_key)::text)) > 0))';
COMMENT ON CONSTRAINT ck_agent_profile_version_positive ON public.agent_instance IS '数据有效性约束：CHECK ((profile_version > 0))';
COMMENT ON CONSTRAINT ck_agent_version_non_negative ON public.agent_instance IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT agent_instance_pkey ON public.agent_instance IS '主键：唯一标识Agent 卡片配置的记录  (id)';
COMMENT ON CONSTRAINT uq_agent_instance_project_id ON public.agent_instance IS '唯一约束：禁止作用域内重复记录  (project_id, id)';
COMMENT ON CONSTRAINT fk_agent_instance_project ON public.agent_instance IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE';
COMMENT ON INDEX public.agent_instance_pkey IS '支撑主键 agent_instance.agent_instance_pkey';
COMMENT ON INDEX public.uq_agent_instance_project_id IS '支撑唯一约束 agent_instance.uq_agent_instance_project_id';

CREATE INDEX ix_agent_instance_project ON public.agent_instance USING btree (project_id, created_at, id);
COMMENT ON INDEX public.ix_agent_instance_project IS '查询索引：支持Agent 卡片配置的定位与排序；USING btree (project_id, created_at, id)';

-- 业务产物身份及资源库默认版本；内容保存于不可变版本。
CREATE TABLE public.artifact (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    kind character varying(32) NOT NULL, -- 业务类型，允许值由 CHECK 约束限定
    title character varying(160) NOT NULL, -- 显示标题
    resource_default_version_id uuid, -- 资源默认版本指针；应用校验所属产物并执行 CAS，不设循环外键
    archived_at timestamp with time zone, -- 归档时间；未归档时为空
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT ck_artifact_kind CHECK ((kind IN ('TEXT', 'IMAGE', 'VIDEO', 'AUDIO'))),
    CONSTRAINT ck_artifact_title_not_blank CHECK ((length(btrim((title)::text)) > 0)),
    CONSTRAINT ck_artifact_version_non_negative CHECK ((version >= 0)),
    CONSTRAINT artifact_pkey PRIMARY KEY (id),
    CONSTRAINT uq_artifact_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_artifact_project FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.artifact IS '业务产物身份及资源库默认版本；内容保存于不可变版本';
COMMENT ON COLUMN public.artifact.id IS '记录身份';
COMMENT ON COLUMN public.artifact.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.artifact.kind IS '业务类型，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.artifact.title IS '显示标题';
COMMENT ON COLUMN public.artifact.resource_default_version_id IS '资源默认版本指针；应用校验所属产物并执行 CAS，不设循环外键';
COMMENT ON COLUMN public.artifact.archived_at IS '归档时间；未归档时为空';
COMMENT ON COLUMN public.artifact.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.artifact.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.artifact.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT ck_artifact_kind ON public.artifact IS '数据有效性约束：CHECK ((kind IN (''TEXT'', ''IMAGE'', ''VIDEO'', ''AUDIO'')))';
COMMENT ON CONSTRAINT ck_artifact_title_not_blank ON public.artifact IS '数据有效性约束：CHECK ((length(btrim((title)::text)) > 0))';
COMMENT ON CONSTRAINT ck_artifact_version_non_negative ON public.artifact IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT artifact_pkey ON public.artifact IS '主键：唯一标识业务产物身份及资源库默认版本的记录  (id)';
COMMENT ON CONSTRAINT uq_artifact_project_id ON public.artifact IS '唯一约束：禁止作用域内重复记录  (project_id, id)';
COMMENT ON CONSTRAINT fk_artifact_project ON public.artifact IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE';
COMMENT ON INDEX public.artifact_pkey IS '支撑主键 artifact.artifact_pkey';
COMMENT ON INDEX public.uq_artifact_project_id IS '支撑唯一约束 artifact.uq_artifact_project_id';

CREATE INDEX ix_artifact_project_active ON public.artifact USING btree (project_id, archived_at, created_at DESC);
COMMENT ON INDEX public.ix_artifact_project_active IS '查询索引：支持业务产物身份及资源库默认版本的定位与排序；USING btree (project_id, archived_at, created_at DESC)';

-- 已校验、归档并发布的媒体字节及完整性元数据。
CREATE TABLE public.asset (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    media_kind character varying(16) NOT NULL, -- 媒体字节类型
    object_key character varying(200) NOT NULL, -- 存储配置内的相对对象键，不是任意文件路径
    content_type character varying(80) NOT NULL, -- 实际校验的媒体 MIME 类型
    byte_size bigint NOT NULL, -- 归档字节数
    sha256 character(64) NOT NULL, -- 归档字节的 SHA-256 完整性摘要
    width integer, -- 媒体像素宽度或画布卡片宽度
    height integer, -- 媒体像素高度或画布卡片高度
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    thumbnail_key character varying(200), -- Private, bounded PNG preview created before a new IMAGE Asset becomes READY; for VIDEO it is the extracted cover frame.
    thumbnail_byte_size bigint, -- Preview or cover frame byte size; NULL only for media archived before V21.
    thumbnail_sha256 character(64), -- Preview or cover frame SHA-256 digest.
    duration_ms integer, -- Verified MP4 duration at archive time; null only for images or pre-V35 video assets.
    CONSTRAINT ck_asset_dimensions CHECK ((((width IS NULL) AND (height IS NULL)) OR ((width > 0) AND (height > 0)))),
    CONSTRAINT ck_asset_kind CHECK ((media_kind IN ('IMAGE', 'VIDEO', 'AUDIO'))),
    CONSTRAINT ck_asset_media_duration CHECK (((((media_kind)::text = 'IMAGE'::text) AND (duration_ms IS NULL)) OR (((media_kind)::text = 'VIDEO'::text) AND ((duration_ms IS NULL) OR ((duration_ms >= 1) AND (duration_ms <= 60000)))) OR (((media_kind)::text = 'AUDIO'::text) AND (duration_ms IS NOT NULL) AND ((duration_ms >= 1) AND (duration_ms <= 600000)) AND (width IS NULL) AND (height IS NULL)))),
    CONSTRAINT ck_asset_sha256 CHECK ((sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_asset_size CHECK ((byte_size > 0)),
    CONSTRAINT ck_asset_thumbnail_complete CHECK ((((thumbnail_key IS NULL) AND (thumbnail_byte_size IS NULL) AND (thumbnail_sha256 IS NULL)) OR ((thumbnail_key IS NOT NULL) AND (thumbnail_byte_size > 0) AND (thumbnail_sha256 ~ '^[0-9a-f]{64}$'::text)))),
    CONSTRAINT asset_pkey PRIMARY KEY (id),
    CONSTRAINT uq_asset_object_key UNIQUE (object_key),
    CONSTRAINT uq_asset_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_asset_thumbnail_key UNIQUE (thumbnail_key),
    CONSTRAINT fk_asset_project FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.asset IS '已校验、归档并发布的媒体字节及完整性元数据';
COMMENT ON COLUMN public.asset.id IS '记录身份';
COMMENT ON COLUMN public.asset.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.asset.media_kind IS '媒体字节类型';
COMMENT ON COLUMN public.asset.object_key IS '存储配置内的相对对象键，不是任意文件路径';
COMMENT ON COLUMN public.asset.content_type IS '实际校验的媒体 MIME 类型';
COMMENT ON COLUMN public.asset.byte_size IS '归档字节数';
COMMENT ON COLUMN public.asset.sha256 IS '归档字节的 SHA-256 完整性摘要';
COMMENT ON COLUMN public.asset.width IS '媒体像素宽度或画布卡片宽度';
COMMENT ON COLUMN public.asset.height IS '媒体像素高度或画布卡片高度';
COMMENT ON COLUMN public.asset.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.asset.thumbnail_key IS 'Private, bounded PNG preview created before a new IMAGE Asset becomes READY; for VIDEO it is the extracted cover frame.';
COMMENT ON COLUMN public.asset.thumbnail_byte_size IS 'Preview or cover frame byte size; NULL only for media archived before V21.';
COMMENT ON COLUMN public.asset.thumbnail_sha256 IS 'Preview or cover frame SHA-256 digest.';
COMMENT ON COLUMN public.asset.duration_ms IS 'Verified MP4 duration at archive time; null only for images or pre-V35 video assets.';
COMMENT ON CONSTRAINT ck_asset_dimensions ON public.asset IS '数据有效性约束：CHECK ((((width IS NULL) AND (height IS NULL)) OR ((width > 0) AND (height > 0))))';
COMMENT ON CONSTRAINT ck_asset_kind ON public.asset IS '数据有效性约束：CHECK ((media_kind IN (''IMAGE'', ''VIDEO'', ''AUDIO'')))';
COMMENT ON CONSTRAINT ck_asset_media_duration ON public.asset IS '数据有效性约束：CHECK (((((media_kind)::text = ''IMAGE''::text) AND (duration_ms IS NULL)) OR (((media_kind)::text = ''VIDEO''::text) AND ((duration_ms IS NULL) OR ((duration_ms >= 1) AND (duration_ms <= 60000)))) OR (((media_kind)::text = ''AUDIO''::text) AND (duration_ms IS NOT NULL) AND ((duration_ms >= 1) AND (duration_ms <= 600000)) AND (width IS NULL) AND (height IS NULL))))';
COMMENT ON CONSTRAINT ck_asset_sha256 ON public.asset IS '数据有效性约束：CHECK ((sha256 ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT ck_asset_size ON public.asset IS '数据有效性约束：CHECK ((byte_size > 0))';
COMMENT ON CONSTRAINT ck_asset_thumbnail_complete ON public.asset IS '数据有效性约束：CHECK ((((thumbnail_key IS NULL) AND (thumbnail_byte_size IS NULL) AND (thumbnail_sha256 IS NULL)) OR ((thumbnail_key IS NOT NULL) AND (thumbnail_byte_size > 0) AND (thumbnail_sha256 ~ ''^[0-9a-f]{64}$''::text))))';
COMMENT ON CONSTRAINT asset_pkey ON public.asset IS '主键：唯一标识已校验、归档并发布的媒体字节及完整性元数据的记录  (id)';
COMMENT ON CONSTRAINT uq_asset_object_key ON public.asset IS '唯一约束：禁止作用域内重复记录  (object_key)';
COMMENT ON CONSTRAINT uq_asset_project_id ON public.asset IS '唯一约束：禁止作用域内重复记录  (project_id, id)';
COMMENT ON CONSTRAINT uq_asset_thumbnail_key ON public.asset IS '唯一约束：禁止作用域内重复记录  (thumbnail_key)';
COMMENT ON CONSTRAINT fk_asset_project ON public.asset IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE';
COMMENT ON INDEX public.asset_pkey IS '支撑主键 asset.asset_pkey';
COMMENT ON INDEX public.uq_asset_object_key IS '支撑唯一约束 asset.uq_asset_object_key';
COMMENT ON INDEX public.uq_asset_project_id IS '支撑唯一约束 asset.uq_asset_project_id';
COMMENT ON INDEX public.uq_asset_thumbnail_key IS '支撑唯一约束 asset.uq_asset_thumbnail_key';

CREATE INDEX ix_asset_project_created ON public.asset USING btree (project_id, created_at DESC);
COMMENT ON INDEX public.ix_asset_project_created IS '查询索引：支持已校验、归档并发布的媒体字节及完整性元数据的定位与排序；USING btree (project_id, created_at DESC)';

-- 媒体字节所在存储配置及可读取路由；独立于业务产物版本。
CREATE TABLE public.asset_storage_route (
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    asset_id uuid NOT NULL, -- 已归档媒体字节身份
    media_kind character varying(10) NOT NULL, -- 媒体字节类型
    profile_id uuid, -- 资产使用的固定存储配置
    metadata_json jsonb, -- 存储对象元数据，包含定位和完整性验证所需信息
    ready boolean DEFAULT false NOT NULL, -- 存储路由已完成归档且允许读取
    CONSTRAINT asset_storage_route_check CHECK (((NOT ready) OR (metadata_json IS NOT NULL))),
    CONSTRAINT asset_storage_route_media_kind_check CHECK ((media_kind IN ('IMAGE', 'VIDEO', 'AUDIO'))),
    CONSTRAINT asset_storage_route_metadata_json_check CHECK (((metadata_json IS NULL) OR ((jsonb_typeof(metadata_json) = 'object'::text) AND (metadata_json ? 'schemaVersion'::text) AND ((metadata_json ->> 'schemaVersion'::text) = '1'::text)))),
    CONSTRAINT asset_storage_route_pkey PRIMARY KEY (project_id, asset_id),
    CONSTRAINT asset_storage_route_profile_id_fkey FOREIGN KEY (profile_id) REFERENCES public.storage_profile(id),
    CONSTRAINT asset_storage_route_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.asset_storage_route IS '媒体字节所在存储配置及可读取路由；独立于业务产物版本';
COMMENT ON COLUMN public.asset_storage_route.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.asset_storage_route.asset_id IS '已归档媒体字节身份';
COMMENT ON COLUMN public.asset_storage_route.media_kind IS '媒体字节类型';
COMMENT ON COLUMN public.asset_storage_route.profile_id IS '资产使用的固定存储配置';
COMMENT ON COLUMN public.asset_storage_route.metadata_json IS '存储对象元数据，包含定位和完整性验证所需信息';
COMMENT ON COLUMN public.asset_storage_route.ready IS '存储路由已完成归档且允许读取';
COMMENT ON CONSTRAINT asset_storage_route_check ON public.asset_storage_route IS '数据有效性约束：CHECK (((NOT ready) OR (metadata_json IS NOT NULL)))';
COMMENT ON CONSTRAINT asset_storage_route_media_kind_check ON public.asset_storage_route IS '数据有效性约束：CHECK ((media_kind IN (''IMAGE'', ''VIDEO'', ''AUDIO'')))';
COMMENT ON CONSTRAINT asset_storage_route_metadata_json_check ON public.asset_storage_route IS '数据有效性约束：CHECK (((metadata_json IS NULL) OR ((jsonb_typeof(metadata_json) = ''object''::text) AND (metadata_json ? ''schemaVersion''::text) AND ((metadata_json ->> ''schemaVersion''::text) = ''1''::text))))';
COMMENT ON CONSTRAINT asset_storage_route_pkey ON public.asset_storage_route IS '主键：唯一标识媒体字节所在存储配置及可读取路由的记录  (project_id, asset_id)';
COMMENT ON CONSTRAINT asset_storage_route_profile_id_fkey ON public.asset_storage_route IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (profile_id) REFERENCES public.storage_profile(id)';
COMMENT ON CONSTRAINT asset_storage_route_project_id_fkey ON public.asset_storage_route IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE';
COMMENT ON INDEX public.asset_storage_route_pkey IS '支撑主键 asset_storage_route.asset_storage_route_pkey';

-- 个人素材库条目、固定内容与精确导入来源。
CREATE TABLE public.library_entry (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    name character varying(160) NOT NULL, -- 显示名称
    category character varying(16) NOT NULL, -- 目录分类
    kind character varying(16) NOT NULL, -- 业务类型，允许值由 CHECK 约束限定
    content_schema_version integer DEFAULT 1 NOT NULL, -- 独立文本内容格式版本
    text_content jsonb, -- 独立保存的文字内容
    file_id uuid, -- 个人素材库归档文件
    source_version_id uuid, -- 引用来源或导入生成的不可变版本
    source_json jsonb NOT NULL, -- 固定导入来源审计信息
    favorite boolean DEFAULT false NOT NULL, -- 是否加入收藏
    trashed_at timestamp with time zone, -- 移入回收站的时间；未删除时为空
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT library_entry_category_check CHECK ((category IN ('CHARACTER', 'SCENE', 'PROP', 'OTHER'))),
    CONSTRAINT library_entry_check CHECK (((((kind)::text = 'TEXT'::text) AND (file_id IS NULL) AND (jsonb_typeof(text_content) = 'object'::text) AND ((text_content ->> 'format'::text) = ANY (ARRAY['PLAIN_TEXT'::text, 'MARKDOWN'::text])) AND ((length(btrim((text_content ->> 'text'::text))) >= 1) AND (length(btrim((text_content ->> 'text'::text))) <= 20000))) OR (((kind)::text <> 'TEXT'::text) AND (file_id IS NOT NULL) AND (text_content IS NULL)))),
    CONSTRAINT library_entry_complete CHECK ((((kind)::text <> 'TEXT'::text) OR ((text_content IS NOT NULL) AND (text_content ?& ARRAY['format'::text, 'text'::text]) AND (jsonb_typeof((text_content -> 'text'::text)) = 'string'::text)))),
    CONSTRAINT library_entry_content_schema_version_check CHECK ((content_schema_version = 1)),
    CONSTRAINT library_entry_kind_check CHECK ((kind IN ('TEXT', 'IMAGE', 'VIDEO', 'AUDIO'))),
    CONSTRAINT library_entry_name_check CHECK ((length(btrim((name)::text)) > 0)),
    CONSTRAINT library_entry_source_json_check CHECK ((jsonb_typeof(source_json) = 'object'::text)),
    CONSTRAINT library_entry_source_schema CHECK (((source_json ? 'schemaVersion'::text) AND ((source_json ->> 'schemaVersion'::text) = '1'::text))),
    CONSTRAINT library_entry_version_check CHECK ((version >= 0)),
    CONSTRAINT library_entry_pkey PRIMARY KEY (id),
    CONSTRAINT library_entry_owner_id_id_key UNIQUE (owner_id, id),
    CONSTRAINT library_entry_owner_id_source_version_id_key UNIQUE (owner_id, source_version_id),
    CONSTRAINT library_entry_owner_id_file_id_kind_fkey FOREIGN KEY (owner_id, file_id, kind) REFERENCES public.library_file(owner_id, id, kind),
    CONSTRAINT library_entry_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id)
);

COMMENT ON CONSTRAINT library_entry_owner_id_fkey ON public.library_entry IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON TABLE public.library_entry IS '个人素材库条目、固定内容与精确导入来源';
COMMENT ON COLUMN public.library_entry.id IS '记录身份';
COMMENT ON COLUMN public.library_entry.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.library_entry.name IS '显示名称';
COMMENT ON COLUMN public.library_entry.category IS '目录分类';
COMMENT ON COLUMN public.library_entry.kind IS '业务类型，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.library_entry.content_schema_version IS '独立文本内容格式版本';
COMMENT ON COLUMN public.library_entry.text_content IS '独立保存的文字内容';
COMMENT ON COLUMN public.library_entry.file_id IS '个人素材库归档文件';
COMMENT ON COLUMN public.library_entry.source_version_id IS '引用来源或导入生成的不可变版本';
COMMENT ON COLUMN public.library_entry.source_json IS '固定导入来源审计信息';
COMMENT ON COLUMN public.library_entry.favorite IS '是否加入收藏';
COMMENT ON COLUMN public.library_entry.trashed_at IS '移入回收站的时间；未删除时为空';
COMMENT ON COLUMN public.library_entry.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.library_entry.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.library_entry.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT library_entry_category_check ON public.library_entry IS '数据有效性约束：CHECK ((category IN (''CHARACTER'', ''SCENE'', ''PROP'', ''OTHER'')))';
COMMENT ON CONSTRAINT library_entry_check ON public.library_entry IS '数据有效性约束：CHECK (((((kind)::text = ''TEXT''::text) AND (file_id IS NULL) AND (jsonb_typeof(text_content) = ''object''::text) AND ((text_content ->> ''format''::text) = ANY (ARRAY[''PLAIN_TEXT''::text, ''MARKDOWN''::text])) AND ((length(btrim((text_content ->> ''text''::text))) >= 1) AND (length(btrim((text_content ->> ''text''::text))) <= 20000))) OR (((kind)::text <> ''TEXT''::text) AND (file_id IS NOT NULL) AND (text_content IS NULL))))';
COMMENT ON CONSTRAINT library_entry_complete ON public.library_entry IS '数据有效性约束：CHECK ((((kind)::text <> ''TEXT''::text) OR ((text_content IS NOT NULL) AND (text_content ?& ARRAY[''format''::text, ''text''::text]) AND (jsonb_typeof((text_content -> ''text''::text)) = ''string''::text))))';
COMMENT ON CONSTRAINT library_entry_content_schema_version_check ON public.library_entry IS '数据有效性约束：CHECK ((content_schema_version = 1))';
COMMENT ON CONSTRAINT library_entry_kind_check ON public.library_entry IS '数据有效性约束：CHECK ((kind IN (''TEXT'', ''IMAGE'', ''VIDEO'', ''AUDIO'')))';
COMMENT ON CONSTRAINT library_entry_name_check ON public.library_entry IS '数据有效性约束：CHECK ((length(btrim((name)::text)) > 0))';
COMMENT ON CONSTRAINT library_entry_source_json_check ON public.library_entry IS '数据有效性约束：CHECK ((jsonb_typeof(source_json) = ''object''::text))';
COMMENT ON CONSTRAINT library_entry_source_schema ON public.library_entry IS '数据有效性约束：CHECK (((source_json ? ''schemaVersion''::text) AND ((source_json ->> ''schemaVersion''::text) = ''1''::text)))';
COMMENT ON CONSTRAINT library_entry_version_check ON public.library_entry IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT library_entry_owner_id_id_key ON public.library_entry IS '唯一约束：禁止作用域内重复记录  (owner_id, id)';
COMMENT ON CONSTRAINT library_entry_owner_id_source_version_id_key ON public.library_entry IS '唯一约束：禁止作用域内重复记录  (owner_id, source_version_id)';
COMMENT ON CONSTRAINT library_entry_pkey ON public.library_entry IS '主键：唯一标识个人素材库条目、固定内容与精确导入来源的记录  (id)';
COMMENT ON CONSTRAINT library_entry_owner_id_file_id_kind_fkey ON public.library_entry IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id, file_id, kind) REFERENCES public.library_file(owner_id, id, kind)';
COMMENT ON INDEX public.library_entry_pkey IS '支撑主键 library_entry.library_entry_pkey';
COMMENT ON INDEX public.library_entry_owner_id_id_key IS '支撑唯一约束 library_entry.library_entry_owner_id_id_key';
COMMENT ON INDEX public.library_entry_owner_id_source_version_id_key IS '支撑唯一约束 library_entry.library_entry_owner_id_source_version_id_key';

CREATE INDEX ix_library_entry_list ON public.library_entry USING btree (owner_id, trashed_at, category, created_at DESC, id DESC);
COMMENT ON INDEX public.ix_library_entry_list IS '查询索引：支持个人素材库条目、固定内容与精确导入来源的定位与排序；USING btree (owner_id, trashed_at, category, created_at DESC, id DESC)';

-- 媒体能力创建命令的幂等键与载荷摘要。
CREATE TABLE public.media_capability_create_key (
    idempotency_key character varying(160) NOT NULL, -- 作用域内的幂等命令键
    payload_sha256 character(64) NOT NULL, -- 规范化创建请求 SHA-256 摘要
    capability_id uuid NOT NULL, -- 固定媒体能力身份
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT media_capability_create_key_payload_sha256_check CHECK ((payload_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT media_capability_create_key_pkey PRIMARY KEY (idempotency_key),
    CONSTRAINT media_capability_create_key_capability_id_fkey FOREIGN KEY (capability_id) REFERENCES public.media_capability(id) DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON TABLE public.media_capability_create_key IS '媒体能力创建命令的幂等键与载荷摘要';
COMMENT ON COLUMN public.media_capability_create_key.idempotency_key IS '作用域内的幂等命令键';
COMMENT ON COLUMN public.media_capability_create_key.payload_sha256 IS '规范化创建请求 SHA-256 摘要';
COMMENT ON COLUMN public.media_capability_create_key.capability_id IS '固定媒体能力身份';
COMMENT ON COLUMN public.media_capability_create_key.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT media_capability_create_key_payload_sha256_check ON public.media_capability_create_key IS '数据有效性约束：CHECK ((payload_sha256 ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT media_capability_create_key_pkey ON public.media_capability_create_key IS '主键：唯一标识媒体能力创建命令的幂等键与载荷摘要的记录  (idempotency_key)';
COMMENT ON CONSTRAINT media_capability_create_key_capability_id_fkey ON public.media_capability_create_key IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (capability_id) REFERENCES public.media_capability(id) DEFERRABLE INITIALLY DEFERRED';
COMMENT ON INDEX public.media_capability_create_key_pkey IS '支撑主键 media_capability_create_key.media_capability_create_key_pkey';

-- 不可变媒体适配器输入契约、映射摘要与能力规格。
CREATE TABLE public.media_capability_version (
    capability_id uuid NOT NULL, -- 固定媒体能力身份
    version integer NOT NULL, -- 能力内部的不可变发布版本
    adapter_id character varying(80) NOT NULL, -- 受控媒体适配器标识
    mapping_sha256 character(64) NOT NULL, -- 固定输入契约映射的 SHA-256 摘要
    spec_json jsonb NOT NULL, -- 不可变媒体能力规格和输入契约
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT media_capability_version_adapter_id_check CHECK ((length(btrim((adapter_id)::text)) > 0)),
    CONSTRAINT media_capability_version_mapping_sha256_check CHECK ((mapping_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT media_capability_version_spec_json_check CHECK ((jsonb_typeof(spec_json) = 'object'::text)),
    CONSTRAINT media_capability_version_version_check CHECK ((version > 0)),
    CONSTRAINT media_capability_version_pkey PRIMARY KEY (capability_id, version),
    CONSTRAINT media_capability_version_capability_id_fkey FOREIGN KEY (capability_id) REFERENCES public.media_capability(id)
);

COMMENT ON TABLE public.media_capability_version IS '不可变媒体适配器输入契约、映射摘要与能力规格';
COMMENT ON COLUMN public.media_capability_version.capability_id IS '固定媒体能力身份';
COMMENT ON COLUMN public.media_capability_version.version IS '能力内部的不可变发布版本';
COMMENT ON COLUMN public.media_capability_version.adapter_id IS '受控媒体适配器标识';
COMMENT ON COLUMN public.media_capability_version.mapping_sha256 IS '固定输入契约映射的 SHA-256 摘要';
COMMENT ON COLUMN public.media_capability_version.spec_json IS '不可变媒体能力规格和输入契约';
COMMENT ON COLUMN public.media_capability_version.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT media_capability_version_adapter_id_check ON public.media_capability_version IS '数据有效性约束：CHECK ((length(btrim((adapter_id)::text)) > 0))';
COMMENT ON CONSTRAINT media_capability_version_mapping_sha256_check ON public.media_capability_version IS '数据有效性约束：CHECK ((mapping_sha256 ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT media_capability_version_spec_json_check ON public.media_capability_version IS '数据有效性约束：CHECK ((jsonb_typeof(spec_json) = ''object''::text))';
COMMENT ON CONSTRAINT media_capability_version_version_check ON public.media_capability_version IS '数据有效性约束：CHECK ((version > 0))';
COMMENT ON CONSTRAINT media_capability_version_pkey ON public.media_capability_version IS '主键：唯一标识不可变媒体适配器输入契约、映射摘要与能力规格的记录  (capability_id, version)';
COMMENT ON CONSTRAINT media_capability_version_capability_id_fkey ON public.media_capability_version IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (capability_id) REFERENCES public.media_capability(id)';
COMMENT ON INDEX public.media_capability_version_pkey IS '支撑主键 media_capability_version.media_capability_version_pkey';

-- 每类媒体操作的默认能力选择及并发控制版本。
CREATE TABLE public.media_default (
    kind character varying(40) NOT NULL, -- 业务类型，允许值由 CHECK 约束限定
    capability_id uuid NOT NULL, -- 固定媒体能力身份
    version bigint NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    CONSTRAINT media_default_kind_check CHECK ((kind IN ('IMAGE_GENERATION', 'VIDEO_GENERATION', 'AUDIO_GENERATION'))),
    CONSTRAINT media_default_version_check CHECK ((version >= 0)),
    CONSTRAINT media_default_pkey PRIMARY KEY (kind),
    CONSTRAINT media_default_capability_id_fkey FOREIGN KEY (capability_id) REFERENCES public.media_capability(id)
);

COMMENT ON TABLE public.media_default IS '每类媒体操作的默认能力选择及并发控制版本';
COMMENT ON COLUMN public.media_default.kind IS '业务类型，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.media_default.capability_id IS '固定媒体能力身份';
COMMENT ON COLUMN public.media_default.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON CONSTRAINT media_default_kind_check ON public.media_default IS '数据有效性约束：CHECK ((kind IN (''IMAGE_GENERATION'', ''VIDEO_GENERATION'', ''AUDIO_GENERATION'')))';
COMMENT ON CONSTRAINT media_default_version_check ON public.media_default IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT media_default_pkey ON public.media_default IS '主键：唯一标识每类媒体操作的默认能力选择及并发控制版本的记录  (kind)';
COMMENT ON CONSTRAINT media_default_capability_id_fkey ON public.media_default IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (capability_id) REFERENCES public.media_capability(id)';
COMMENT ON INDEX public.media_default_pkey IS '支撑主键 media_default.media_default_pkey';

-- 媒体模板引用图片及稳定显示顺序。
CREATE TABLE public.media_template_attachment (
    template_id uuid NOT NULL, -- 媒体模板身份
    image_id uuid NOT NULL, -- 模板参考图片身份
    "position" integer NOT NULL, -- 模板图片的稳定排序位置
    CONSTRAINT media_template_attachment_position_check CHECK ((("position" >= 0) AND ("position" <= 7))),
    CONSTRAINT media_template_attachment_pkey PRIMARY KEY (template_id, "position"),
    CONSTRAINT media_template_attachment_template_id_image_id_key UNIQUE (template_id, image_id),
    CONSTRAINT media_template_attachment_image_id_fkey FOREIGN KEY (image_id) REFERENCES public.media_template_image(id),
    CONSTRAINT media_template_attachment_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.media_template(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.media_template_attachment IS '媒体模板引用图片及稳定显示顺序';
COMMENT ON COLUMN public.media_template_attachment.template_id IS '媒体模板身份';
COMMENT ON COLUMN public.media_template_attachment.image_id IS '模板参考图片身份';
COMMENT ON COLUMN public.media_template_attachment."position" IS '模板图片的稳定排序位置';
COMMENT ON CONSTRAINT media_template_attachment_position_check ON public.media_template_attachment IS '数据有效性约束：CHECK ((("position" >= 0) AND ("position" <= 7)))';
COMMENT ON CONSTRAINT media_template_attachment_pkey ON public.media_template_attachment IS '主键：唯一标识媒体模板引用图片及稳定显示顺序的记录  (template_id, "position")';
COMMENT ON CONSTRAINT media_template_attachment_template_id_image_id_key ON public.media_template_attachment IS '唯一约束：禁止作用域内重复记录  (template_id, image_id)';
COMMENT ON CONSTRAINT media_template_attachment_image_id_fkey ON public.media_template_attachment IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (image_id) REFERENCES public.media_template_image(id)';
COMMENT ON CONSTRAINT media_template_attachment_template_id_fkey ON public.media_template_attachment IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (template_id) REFERENCES public.media_template(id) ON DELETE CASCADE';
COMMENT ON INDEX public.media_template_attachment_pkey IS '支撑主键 media_template_attachment.media_template_attachment_pkey';
COMMENT ON INDEX public.media_template_attachment_template_id_image_id_key IS '支撑唯一约束 media_template_attachment.media_template_attachment_template_id_image_id_key';

-- 模板导入命令的固定输入、幂等摘要和结果。
CREATE TABLE public.media_template_import_command (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    command_key character varying(200) NOT NULL, -- 用户命令幂等键
    payload_hash character(64) NOT NULL, -- 规范化命令载荷 SHA-256 摘要
    input_json jsonb NOT NULL, -- 受理时固定的命令输入
    result_json jsonb, -- 已提交的结构化执行或审批结果
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT media_template_import_command_command_key_check CHECK ((length(btrim((command_key)::text)) > 0)),
    CONSTRAINT media_template_import_command_input_json_check CHECK (((jsonb_typeof(input_json) = 'object'::text) AND ((input_json ->> 'schemaVersion'::text) = '1'::text))),
    CONSTRAINT media_template_import_command_payload_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT media_template_import_command_result_json_check CHECK (((result_json IS NULL) OR (jsonb_typeof(result_json) = 'object'::text))),
    CONSTRAINT media_template_import_command_pkey PRIMARY KEY (id),
    CONSTRAINT media_template_import_command_project_id_command_key_key UNIQUE (project_id, command_key),
    CONSTRAINT media_template_import_command_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id),
    CONSTRAINT media_template_import_command_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.media_template_import_command IS '模板导入命令的固定输入、幂等摘要和结果';
COMMENT ON COLUMN public.media_template_import_command.id IS '记录身份';
COMMENT ON COLUMN public.media_template_import_command.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.media_template_import_command.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.media_template_import_command.command_key IS '用户命令幂等键';
COMMENT ON COLUMN public.media_template_import_command.payload_hash IS '规范化命令载荷 SHA-256 摘要';
COMMENT ON COLUMN public.media_template_import_command.input_json IS '受理时固定的命令输入';
COMMENT ON COLUMN public.media_template_import_command.result_json IS '已提交的结构化执行或审批结果';
COMMENT ON COLUMN public.media_template_import_command.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT media_template_import_command_command_key_check ON public.media_template_import_command IS '数据有效性约束：CHECK ((length(btrim((command_key)::text)) > 0))';
COMMENT ON CONSTRAINT media_template_import_command_input_json_check ON public.media_template_import_command IS '数据有效性约束：CHECK (((jsonb_typeof(input_json) = ''object''::text) AND ((input_json ->> ''schemaVersion''::text) = ''1''::text)))';
COMMENT ON CONSTRAINT media_template_import_command_payload_hash_check ON public.media_template_import_command IS '数据有效性约束：CHECK ((payload_hash ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT media_template_import_command_result_json_check ON public.media_template_import_command IS '数据有效性约束：CHECK (((result_json IS NULL) OR (jsonb_typeof(result_json) = ''object''::text)))';
COMMENT ON CONSTRAINT media_template_import_command_pkey ON public.media_template_import_command IS '主键：唯一标识模板导入命令的固定输入、幂等摘要和结果的记录  (id)';
COMMENT ON CONSTRAINT media_template_import_command_project_id_command_key_key ON public.media_template_import_command IS '唯一约束：禁止作用域内重复记录  (project_id, command_key)';
COMMENT ON CONSTRAINT media_template_import_command_owner_id_fkey ON public.media_template_import_command IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON CONSTRAINT media_template_import_command_project_id_fkey ON public.media_template_import_command IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE';
COMMENT ON INDEX public.media_template_import_command_pkey IS '支撑主键 media_template_import_command.media_template_import_command_pkey';
COMMENT ON INDEX public.media_template_import_command_project_id_command_key_key IS '支撑唯一约束 media_template_import_command.media_template_import_command_project_id_command_key_key';

-- 与业务变化同事务提交的项目事件，项目内序号作为 SSE 水位。
CREATE TABLE public.project_event (
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    seq bigint NOT NULL, -- Commit-safe project-local waterline allocated while holding the project row lock.
    event_id uuid NOT NULL, -- 事件全局去重身份
    type character varying(120) NOT NULL, -- 项目事件类型
    schema_version integer NOT NULL, -- 持久 JSON 内容格式版本
    aggregate_id uuid NOT NULL, -- 变化的业务对象身份
    aggregate_version bigint NOT NULL, -- 变化对象的并发版本
    payload_json jsonb NOT NULL, -- 与业务变更同事务提交的公开事件载荷
    occurred_at timestamp with time zone NOT NULL, -- 业务事件发生时间
    CONSTRAINT ck_project_event_aggregate_version_non_negative CHECK ((aggregate_version >= 0)),
    CONSTRAINT ck_project_event_payload_object CHECK ((jsonb_typeof(payload_json) = 'object'::text)),
    CONSTRAINT ck_project_event_schema_version_positive CHECK ((schema_version > 0)),
    CONSTRAINT ck_project_event_seq_positive CHECK ((seq > 0)),
    CONSTRAINT ck_project_event_type_not_blank CHECK ((length(btrim((type)::text)) > 0)),
    CONSTRAINT project_event_pkey PRIMARY KEY (project_id, seq),
    CONSTRAINT uq_project_event_id UNIQUE (event_id),
    CONSTRAINT fk_project_event_project FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.project_event IS '与业务变化同事务提交的项目事件，项目内序号作为 SSE 水位';
COMMENT ON COLUMN public.project_event.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.project_event.seq IS 'Commit-safe project-local waterline allocated while holding the project row lock.';
COMMENT ON COLUMN public.project_event.event_id IS '事件全局去重身份';
COMMENT ON COLUMN public.project_event.type IS '项目事件类型';
COMMENT ON COLUMN public.project_event.schema_version IS '持久 JSON 内容格式版本';
COMMENT ON COLUMN public.project_event.aggregate_id IS '变化的业务对象身份';
COMMENT ON COLUMN public.project_event.aggregate_version IS '变化对象的并发版本';
COMMENT ON COLUMN public.project_event.payload_json IS '与业务变更同事务提交的公开事件载荷';
COMMENT ON COLUMN public.project_event.occurred_at IS '业务事件发生时间';
COMMENT ON CONSTRAINT ck_project_event_aggregate_version_non_negative ON public.project_event IS '数据有效性约束：CHECK ((aggregate_version >= 0))';
COMMENT ON CONSTRAINT ck_project_event_payload_object ON public.project_event IS '数据有效性约束：CHECK ((jsonb_typeof(payload_json) = ''object''::text))';
COMMENT ON CONSTRAINT ck_project_event_schema_version_positive ON public.project_event IS '数据有效性约束：CHECK ((schema_version > 0))';
COMMENT ON CONSTRAINT ck_project_event_seq_positive ON public.project_event IS '数据有效性约束：CHECK ((seq > 0))';
COMMENT ON CONSTRAINT ck_project_event_type_not_blank ON public.project_event IS '数据有效性约束：CHECK ((length(btrim((type)::text)) > 0))';
COMMENT ON CONSTRAINT project_event_pkey ON public.project_event IS '主键：唯一标识与业务变化同事务提交的项目事件，项目内序号作为 SSE 水位的记录  (project_id, seq)';
COMMENT ON CONSTRAINT uq_project_event_id ON public.project_event IS '唯一约束：禁止作用域内重复记录  (event_id)';
COMMENT ON CONSTRAINT fk_project_event_project ON public.project_event IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE';
COMMENT ON INDEX public.project_event_pkey IS '支撑主键 project_event.project_event_pkey';
COMMENT ON INDEX public.uq_project_event_id IS '支撑唯一约束 project_event.uq_project_event_id';

CREATE INDEX ix_project_event_occurred_at ON public.project_event USING btree (occurred_at);
COMMENT ON INDEX public.ix_project_event_occurred_at IS '查询索引：支持与业务变化同事务提交的项目事件，项目内序号作为 SSE 水位的定位与排序；USING btree (occurred_at)';

-- Skill 可编辑草稿及内容格式版本。
CREATE TABLE public.skill_draft (
    skill_id uuid NOT NULL, -- Skill 业务身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    content_json jsonb NOT NULL, -- 结构化不可变内容，格式由 schemaVersion 和领域校验限定
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT skill_draft_content_json_check CHECK (((jsonb_typeof(content_json) = 'object'::text) AND (content_json @> '{"schemaVersion": 1}'::jsonb))),
    CONSTRAINT skill_draft_version_check CHECK ((version >= 0)),
    CONSTRAINT skill_draft_pkey PRIMARY KEY (skill_id),
    CONSTRAINT skill_draft_owner_id_skill_id_fkey FOREIGN KEY (owner_id, skill_id) REFERENCES public.creative_skill(owner_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE public.skill_draft IS 'Skill 可编辑草稿及内容格式版本';
COMMENT ON COLUMN public.skill_draft.skill_id IS 'Skill 业务身份';
COMMENT ON COLUMN public.skill_draft.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.skill_draft.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.skill_draft.content_json IS '结构化不可变内容，格式由 schemaVersion 和领域校验限定';
COMMENT ON COLUMN public.skill_draft.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT skill_draft_content_json_check ON public.skill_draft IS '数据有效性约束：CHECK (((jsonb_typeof(content_json) = ''object''::text) AND (content_json @> ''{"schemaVersion": 1}''::jsonb)))';
COMMENT ON CONSTRAINT skill_draft_version_check ON public.skill_draft IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT skill_draft_pkey ON public.skill_draft IS '主键：唯一标识Skill 可编辑草稿及内容格式版本的记录  (skill_id)';
COMMENT ON CONSTRAINT skill_draft_owner_id_skill_id_fkey ON public.skill_draft IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id, skill_id) REFERENCES public.creative_skill(owner_id, id) ON DELETE CASCADE';
COMMENT ON INDEX public.skill_draft_pkey IS '支撑主键 skill_draft.skill_draft_pkey';

-- 不可变 Skill 发布版本、内容包及完整性摘要。
CREATE TABLE public.skill_version (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    skill_id uuid NOT NULL, -- Skill 业务身份
    version_number bigint NOT NULL, -- Skill 内部发布版本序号
    bundle_hash character(64) NOT NULL, -- 不可变 Skill 内容包 SHA-256 摘要
    bundle_json jsonb NOT NULL, -- 不可变 Skill 内容包，包含版本与固定素材引用
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT skill_version_bundle_hash_check CHECK ((bundle_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT skill_version_bundle_json_check CHECK (((jsonb_typeof(bundle_json) = 'object'::text) AND (bundle_json @> '{"schemaVersion": 1}'::jsonb))),
    CONSTRAINT skill_version_version_number_check CHECK ((version_number > 0)),
    CONSTRAINT skill_version_pkey PRIMARY KEY (id),
    CONSTRAINT skill_version_owner_id_skill_id_id_key UNIQUE (owner_id, skill_id, id),
    CONSTRAINT skill_version_owner_id_skill_id_version_number_key UNIQUE (owner_id, skill_id, version_number),
    CONSTRAINT skill_version_owner_id_skill_id_fkey FOREIGN KEY (owner_id, skill_id) REFERENCES public.creative_skill(owner_id, id)
);

COMMENT ON TABLE public.skill_version IS '不可变 Skill 发布版本、内容包及完整性摘要';
COMMENT ON COLUMN public.skill_version.id IS '记录身份';
COMMENT ON COLUMN public.skill_version.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.skill_version.skill_id IS 'Skill 业务身份';
COMMENT ON COLUMN public.skill_version.version_number IS 'Skill 内部发布版本序号';
COMMENT ON COLUMN public.skill_version.bundle_hash IS '不可变 Skill 内容包 SHA-256 摘要';
COMMENT ON COLUMN public.skill_version.bundle_json IS '不可变 Skill 内容包，包含版本与固定素材引用';
COMMENT ON COLUMN public.skill_version.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT skill_version_bundle_hash_check ON public.skill_version IS '数据有效性约束：CHECK ((bundle_hash ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT skill_version_bundle_json_check ON public.skill_version IS '数据有效性约束：CHECK (((jsonb_typeof(bundle_json) = ''object''::text) AND (bundle_json @> ''{"schemaVersion": 1}''::jsonb)))';
COMMENT ON CONSTRAINT skill_version_version_number_check ON public.skill_version IS '数据有效性约束：CHECK ((version_number > 0))';
COMMENT ON CONSTRAINT skill_version_owner_id_skill_id_id_key ON public.skill_version IS '唯一约束：禁止作用域内重复记录  (owner_id, skill_id, id)';
COMMENT ON CONSTRAINT skill_version_owner_id_skill_id_version_number_key ON public.skill_version IS '唯一约束：禁止作用域内重复记录  (owner_id, skill_id, version_number)';
COMMENT ON CONSTRAINT skill_version_pkey ON public.skill_version IS '主键：唯一标识不可变 Skill 发布版本、内容包及完整性摘要的记录  (id)';
COMMENT ON CONSTRAINT skill_version_owner_id_skill_id_fkey ON public.skill_version IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id, skill_id) REFERENCES public.creative_skill(owner_id, id)';
COMMENT ON INDEX public.skill_version_pkey IS '支撑主键 skill_version.skill_version_pkey';
COMMENT ON INDEX public.skill_version_owner_id_skill_id_id_key IS '支撑唯一约束 skill_version.skill_version_owner_id_skill_id_id_key';
COMMENT ON INDEX public.skill_version_owner_id_skill_id_version_number_key IS '支撑唯一约束 skill_version.skill_version_owner_id_skill_id_version_number_key';


-- Agent 卡片的持久对话，接收每条用户消息后创建独立预算的 Run。
CREATE TABLE public.agent_conversation (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    agent_instance_id uuid NOT NULL, -- Agent 卡片配置身份
    title character varying(160) NOT NULL, -- 显示标题
    turn_count bigint DEFAULT 0 NOT NULL, -- 已接受的用户消息轮次数
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT ck_conversation_title CHECK ((length(btrim((title)::text)) > 0)),
    CONSTRAINT ck_conversation_turn_count CHECK ((turn_count >= 0)),
    CONSTRAINT ck_conversation_version CHECK ((version >= 0)),
    CONSTRAINT agent_conversation_pkey PRIMARY KEY (id),
    CONSTRAINT uq_conversation_agent_scope UNIQUE (project_id, agent_instance_id, id),
    CONSTRAINT fk_conversation_agent FOREIGN KEY (project_id, agent_instance_id) REFERENCES public.agent_instance(project_id, id)
);

COMMENT ON TABLE public.agent_conversation IS 'Agent 卡片的持久对话，接收每条用户消息后创建独立预算的 Run';
COMMENT ON COLUMN public.agent_conversation.id IS '记录身份';
COMMENT ON COLUMN public.agent_conversation.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.agent_conversation.agent_instance_id IS 'Agent 卡片配置身份';
COMMENT ON COLUMN public.agent_conversation.title IS '显示标题';
COMMENT ON COLUMN public.agent_conversation.turn_count IS '已接受的用户消息轮次数';
COMMENT ON COLUMN public.agent_conversation.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.agent_conversation.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.agent_conversation.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT ck_conversation_title ON public.agent_conversation IS '数据有效性约束：CHECK ((length(btrim((title)::text)) > 0))';
COMMENT ON CONSTRAINT ck_conversation_turn_count ON public.agent_conversation IS '数据有效性约束：CHECK ((turn_count >= 0))';
COMMENT ON CONSTRAINT ck_conversation_version ON public.agent_conversation IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT agent_conversation_pkey ON public.agent_conversation IS '主键：唯一标识Agent 卡片的持久对话，接收每条用户消息后创建独立预算的 Run的记录  (id)';
COMMENT ON CONSTRAINT uq_conversation_agent_scope ON public.agent_conversation IS '唯一约束：禁止作用域内重复记录  (project_id, agent_instance_id, id)';
COMMENT ON CONSTRAINT fk_conversation_agent ON public.agent_conversation IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, agent_instance_id) REFERENCES public.agent_instance(project_id, id)';
COMMENT ON INDEX public.agent_conversation_pkey IS '支撑主键 agent_conversation.agent_conversation_pkey';
COMMENT ON INDEX public.uq_conversation_agent_scope IS '支撑唯一约束 agent_conversation.uq_conversation_agent_scope';

CREATE INDEX ix_conversation_agent_updated ON public.agent_conversation USING btree (project_id, agent_instance_id, updated_at DESC, id DESC);
COMMENT ON INDEX public.ix_conversation_agent_updated IS '查询索引：支持Agent 卡片的持久对话，接收每条用户消息后创建独立预算的 Run的定位与排序；USING btree (project_id, agent_instance_id, updated_at DESC, id DESC)';


-- Agent 选定的 Skill 不可变版本。
CREATE TABLE public.agent_skill_binding (
    agent_id uuid NOT NULL, -- Agent 卡片身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    skill_id uuid NOT NULL, -- Skill 业务身份
    skill_version_id uuid NOT NULL, -- 固定的不可变 Skill 发布版本
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT agent_skill_binding_pkey PRIMARY KEY (agent_id),
    CONSTRAINT agent_skill_binding_owner_id_skill_id_skill_version_id_fkey FOREIGN KEY (owner_id, skill_id, skill_version_id) REFERENCES public.skill_version(owner_id, skill_id, id),
    CONSTRAINT agent_skill_binding_project_id_agent_id_fkey FOREIGN KEY (project_id, agent_id) REFERENCES public.agent_instance(project_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE public.agent_skill_binding IS 'Agent 选定的 Skill 不可变版本';
COMMENT ON COLUMN public.agent_skill_binding.agent_id IS 'Agent 卡片身份';
COMMENT ON COLUMN public.agent_skill_binding.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.agent_skill_binding.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.agent_skill_binding.skill_id IS 'Skill 业务身份';
COMMENT ON COLUMN public.agent_skill_binding.skill_version_id IS '固定的不可变 Skill 发布版本';
COMMENT ON COLUMN public.agent_skill_binding.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT agent_skill_binding_pkey ON public.agent_skill_binding IS '主键：唯一标识Agent 选定的 Skill 不可变版本的记录  (agent_id)';
COMMENT ON CONSTRAINT agent_skill_binding_owner_id_skill_id_skill_version_id_fkey ON public.agent_skill_binding IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id, skill_id, skill_version_id) REFERENCES public.skill_version(owner_id, skill_id, id)';
COMMENT ON CONSTRAINT agent_skill_binding_project_id_agent_id_fkey ON public.agent_skill_binding IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, agent_id) REFERENCES public.agent_instance(project_id, id) ON DELETE CASCADE';
COMMENT ON INDEX public.agent_skill_binding_pkey IS '支撑主键 agent_skill_binding.agent_skill_binding_pkey';

-- 不可变产物内容、固定输入及生成来源；触发器禁止更新和删除。
CREATE TABLE public.artifact_version (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    artifact_id uuid NOT NULL, -- 业务产物身份
    version_no integer NOT NULL, -- 产物内部单调递增的不可变内容版本序号
    schema_version integer NOT NULL, -- 持久 JSON 内容格式版本
    content_json jsonb NOT NULL, -- 结构化不可变内容，格式由 schemaVersion 和领域校验限定
    input_refs_json jsonb NOT NULL, -- 生成时固定的精确输入版本引用
    created_by_kind character varying(32) NOT NULL, -- 内容创建来源类型
    run_id uuid, -- 所属 Agent Run；用户直连任务为空
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    base_version_id uuid, -- 创作所基于的父版本标识；应用校验所属产物后冻结，不设自引用外键
    frozen_input_json jsonb, -- Read-only generation input copied from the accepting Task; null for uploads and text edits.
    CONSTRAINT ck_artifact_content_object CHECK ((jsonb_typeof(content_json) = 'object'::text)),
    CONSTRAINT ck_artifact_created_by_kind CHECK ((created_by_kind IN ('USER', 'AGENT', 'TASK'))),
    CONSTRAINT ck_artifact_input_refs_array CHECK ((jsonb_typeof(input_refs_json) = 'array'::text)),
    CONSTRAINT ck_artifact_schema_version_positive CHECK ((schema_version > 0)),
    CONSTRAINT ck_artifact_version_frozen_input_object CHECK (((frozen_input_json IS NULL) OR (jsonb_typeof(frozen_input_json) = 'object'::text))),
    CONSTRAINT ck_artifact_version_no_positive CHECK ((version_no > 0)),
    CONSTRAINT artifact_version_pkey PRIMARY KEY (id),
    CONSTRAINT uq_artifact_version_artifact_id UNIQUE (artifact_id, id),
    CONSTRAINT uq_artifact_version_number UNIQUE (artifact_id, version_no),
    CONSTRAINT uq_artifact_version_project_artifact_id UNIQUE (project_id, artifact_id, id),
    CONSTRAINT uq_artifact_version_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_artifact_version_artifact FOREIGN KEY (project_id, artifact_id) REFERENCES public.artifact(project_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE public.artifact_version IS '不可变产物内容、固定输入及生成来源；触发器禁止更新和删除';
COMMENT ON COLUMN public.artifact_version.id IS '记录身份';
COMMENT ON COLUMN public.artifact_version.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.artifact_version.artifact_id IS '业务产物身份';
COMMENT ON COLUMN public.artifact_version.version_no IS '产物内部单调递增的不可变内容版本序号';
COMMENT ON COLUMN public.artifact_version.schema_version IS '持久 JSON 内容格式版本';
COMMENT ON COLUMN public.artifact_version.content_json IS '结构化不可变内容，格式由 schemaVersion 和领域校验限定';
COMMENT ON COLUMN public.artifact_version.input_refs_json IS '生成时固定的精确输入版本引用';
COMMENT ON COLUMN public.artifact_version.created_by_kind IS '内容创建来源类型';
COMMENT ON COLUMN public.artifact_version.run_id IS '所属 Agent Run；用户直连任务为空';
COMMENT ON COLUMN public.artifact_version.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.artifact_version.base_version_id IS '创作所基于的父版本标识；应用校验所属产物后冻结，不设自引用外键';
COMMENT ON COLUMN public.artifact_version.frozen_input_json IS 'Read-only generation input copied from the accepting Task; null for uploads and text edits.';
COMMENT ON CONSTRAINT ck_artifact_content_object ON public.artifact_version IS '数据有效性约束：CHECK ((jsonb_typeof(content_json) = ''object''::text))';
COMMENT ON CONSTRAINT ck_artifact_created_by_kind ON public.artifact_version IS '数据有效性约束：CHECK ((created_by_kind IN (''USER'', ''AGENT'', ''TASK'')))';
COMMENT ON CONSTRAINT ck_artifact_input_refs_array ON public.artifact_version IS '数据有效性约束：CHECK ((jsonb_typeof(input_refs_json) = ''array''::text))';
COMMENT ON CONSTRAINT ck_artifact_schema_version_positive ON public.artifact_version IS '数据有效性约束：CHECK ((schema_version > 0))';
COMMENT ON CONSTRAINT ck_artifact_version_frozen_input_object ON public.artifact_version IS '数据有效性约束：CHECK (((frozen_input_json IS NULL) OR (jsonb_typeof(frozen_input_json) = ''object''::text)))';
COMMENT ON CONSTRAINT ck_artifact_version_no_positive ON public.artifact_version IS '数据有效性约束：CHECK ((version_no > 0))';
COMMENT ON CONSTRAINT artifact_version_pkey ON public.artifact_version IS '主键：唯一标识不可变产物内容、固定输入及生成来源的记录  (id)';
COMMENT ON CONSTRAINT uq_artifact_version_artifact_id ON public.artifact_version IS '唯一约束：禁止作用域内重复记录  (artifact_id, id)';
COMMENT ON CONSTRAINT uq_artifact_version_number ON public.artifact_version IS '唯一约束：禁止作用域内重复记录  (artifact_id, version_no)';
COMMENT ON CONSTRAINT uq_artifact_version_project_artifact_id ON public.artifact_version IS '唯一约束：禁止作用域内重复记录  (project_id, artifact_id, id)';
COMMENT ON CONSTRAINT uq_artifact_version_project_id ON public.artifact_version IS '唯一约束：禁止作用域内重复记录  (project_id, id)';
COMMENT ON CONSTRAINT fk_artifact_version_artifact ON public.artifact_version IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, artifact_id) REFERENCES public.artifact(project_id, id) ON DELETE CASCADE';
COMMENT ON INDEX public.artifact_version_pkey IS '支撑主键 artifact_version.artifact_version_pkey';
COMMENT ON INDEX public.uq_artifact_version_artifact_id IS '支撑唯一约束 artifact_version.uq_artifact_version_artifact_id';
COMMENT ON INDEX public.uq_artifact_version_number IS '支撑唯一约束 artifact_version.uq_artifact_version_number';
COMMENT ON INDEX public.uq_artifact_version_project_artifact_id IS '支撑唯一约束 artifact_version.uq_artifact_version_project_artifact_id';
COMMENT ON INDEX public.uq_artifact_version_project_id IS '支撑唯一约束 artifact_version.uq_artifact_version_project_id';

CREATE INDEX ix_artifact_version_history ON public.artifact_version USING btree (artifact_id, version_no DESC);
COMMENT ON INDEX public.ix_artifact_version_history IS '查询索引：支持不可变产物内容、固定输入及生成来源的定位与排序；USING btree (artifact_id, version_no DESC)';

CREATE TRIGGER artifact_version_no_delete BEFORE DELETE ON public.artifact_version FOR EACH ROW EXECUTE FUNCTION public.reject_artifact_version_mutation();
COMMENT ON TRIGGER artifact_version_no_delete ON public.artifact_version IS '在修改产物版本前拒绝操作，保护不可变内容及精确引用';

CREATE TRIGGER artifact_version_no_update BEFORE UPDATE ON public.artifact_version FOR EACH ROW EXECUTE FUNCTION public.reject_artifact_version_mutation();
COMMENT ON TRIGGER artifact_version_no_update ON public.artifact_version IS '在修改产物版本前拒绝操作，保护不可变内容及精确引用';


-- 模板导入命令固定的参考图片来源。
CREATE TABLE public.media_template_import_source (
    command_id uuid NOT NULL, -- 模板导入命令身份
    image_id uuid NOT NULL, -- 模板参考图片身份
    CONSTRAINT media_template_import_source_pkey PRIMARY KEY (command_id, image_id),
    CONSTRAINT media_template_import_source_command_id_fkey FOREIGN KEY (command_id) REFERENCES public.media_template_import_command(id) ON DELETE CASCADE,
    CONSTRAINT media_template_import_source_image_id_fkey FOREIGN KEY (image_id) REFERENCES public.media_template_image(id)
);

COMMENT ON TABLE public.media_template_import_source IS '模板导入命令固定的参考图片来源';
COMMENT ON COLUMN public.media_template_import_source.command_id IS '模板导入命令身份';
COMMENT ON COLUMN public.media_template_import_source.image_id IS '模板参考图片身份';
COMMENT ON CONSTRAINT media_template_import_source_pkey ON public.media_template_import_source IS '主键：唯一标识模板导入命令固定的参考图片来源的记录  (command_id, image_id)';
COMMENT ON CONSTRAINT media_template_import_source_command_id_fkey ON public.media_template_import_source IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (command_id) REFERENCES public.media_template_import_command(id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT media_template_import_source_image_id_fkey ON public.media_template_import_source IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (image_id) REFERENCES public.media_template_image(id)';
COMMENT ON INDEX public.media_template_import_source_pkey IS '支撑主键 media_template_import_source.media_template_import_source_pkey';

-- Agent Skill 绑定命令的幂等摘要与响应。
CREATE TABLE public.skill_binding_command (
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    agent_id uuid NOT NULL, -- Agent 卡片身份
    command_key character varying(200) NOT NULL, -- 用户命令幂等键
    payload_hash character(64) NOT NULL, -- 规范化命令载荷 SHA-256 摘要
    response_json jsonb NOT NULL, -- 已提交的命令响应或完整模型响应
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT skill_binding_command_command_key_check CHECK ((length(btrim((command_key)::text)) > 0)),
    CONSTRAINT skill_binding_command_payload_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT skill_binding_command_response_json_check CHECK ((jsonb_typeof(response_json) = 'object'::text)),
    CONSTRAINT skill_binding_command_pkey PRIMARY KEY (owner_id, project_id, command_key),
    CONSTRAINT skill_binding_command_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id),
    CONSTRAINT skill_binding_command_project_id_agent_id_fkey FOREIGN KEY (project_id, agent_id) REFERENCES public.agent_instance(project_id, id) ON DELETE CASCADE,
    CONSTRAINT skill_binding_command_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.skill_binding_command IS 'Agent Skill 绑定命令的幂等摘要与响应';
COMMENT ON COLUMN public.skill_binding_command.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.skill_binding_command.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.skill_binding_command.agent_id IS 'Agent 卡片身份';
COMMENT ON COLUMN public.skill_binding_command.command_key IS '用户命令幂等键';
COMMENT ON COLUMN public.skill_binding_command.payload_hash IS '规范化命令载荷 SHA-256 摘要';
COMMENT ON COLUMN public.skill_binding_command.response_json IS '已提交的命令响应或完整模型响应';
COMMENT ON COLUMN public.skill_binding_command.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT skill_binding_command_command_key_check ON public.skill_binding_command IS '数据有效性约束：CHECK ((length(btrim((command_key)::text)) > 0))';
COMMENT ON CONSTRAINT skill_binding_command_payload_hash_check ON public.skill_binding_command IS '数据有效性约束：CHECK ((payload_hash ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT skill_binding_command_response_json_check ON public.skill_binding_command IS '数据有效性约束：CHECK ((jsonb_typeof(response_json) = ''object''::text))';
COMMENT ON CONSTRAINT skill_binding_command_pkey ON public.skill_binding_command IS '主键：唯一标识Agent Skill 绑定命令的幂等摘要与响应的记录  (owner_id, project_id, command_key)';
COMMENT ON CONSTRAINT skill_binding_command_owner_id_fkey ON public.skill_binding_command IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON CONSTRAINT skill_binding_command_project_id_agent_id_fkey ON public.skill_binding_command IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, agent_id) REFERENCES public.agent_instance(project_id, id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT skill_binding_command_project_id_fkey ON public.skill_binding_command IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE';
COMMENT ON INDEX public.skill_binding_command_pkey IS '支撑主键 skill_binding_command.skill_binding_command_pkey';

-- 项目内固定 Skill 版本的安装操作、租约、结果和失败清理。
CREATE TABLE public.skill_install_operation (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    skill_id uuid NOT NULL, -- Skill 业务身份
    skill_version_id uuid NOT NULL, -- 固定的不可变 Skill 发布版本
    input_json jsonb NOT NULL, -- 受理时固定的命令输入
    result_json jsonb, -- 已提交的结构化执行或审批结果
    status character varying(16) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    epoch bigint DEFAULT 0 NOT NULL, -- 操作租约的 fencing epoch，旧执行者不得回写
    lease_until timestamp with time zone, -- 当前认领租约过期时间
    error_code character varying(80), -- 稳定错误代码，不含堆栈或凭据
    error_detail character varying(500), -- 已脱敏的公开错误说明
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    cleanup_json jsonb DEFAULT '[]'::jsonb NOT NULL, -- 失败安装需清理的已创建资产引用
    CONSTRAINT skill_install_operation_cleanup_json_check CHECK ((jsonb_typeof(cleanup_json) = 'array'::text)),
    CONSTRAINT skill_install_operation_epoch_check CHECK ((epoch >= 0)),
    CONSTRAINT skill_install_operation_input_json_check CHECK (((jsonb_typeof(input_json) = 'object'::text) AND (input_json @> '{"schemaVersion": 1}'::jsonb))),
    CONSTRAINT skill_install_operation_lease_check CHECK (((status IN ('PREPARING', 'CLEANING')) = (lease_until IS NOT NULL))),
    CONSTRAINT skill_install_operation_status_check CHECK ((status IN ('ACCEPTED', 'PREPARING', 'SUCCEEDED', 'FAILED', 'CLEANING'))),
    CONSTRAINT skill_install_operation_pkey PRIMARY KEY (id),
    CONSTRAINT skill_install_operation_owner_id_project_id_skill_version_i_key UNIQUE (owner_id, project_id, skill_version_id),
    CONSTRAINT skill_install_operation_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id),
    CONSTRAINT skill_install_operation_owner_id_skill_id_skill_version_id_fkey FOREIGN KEY (owner_id, skill_id, skill_version_id) REFERENCES public.skill_version(owner_id, skill_id, id),
    CONSTRAINT skill_install_operation_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.skill_install_operation IS '项目内固定 Skill 版本的安装操作、租约、结果和失败清理';
COMMENT ON COLUMN public.skill_install_operation.id IS '记录身份';
COMMENT ON COLUMN public.skill_install_operation.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.skill_install_operation.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.skill_install_operation.skill_id IS 'Skill 业务身份';
COMMENT ON COLUMN public.skill_install_operation.skill_version_id IS '固定的不可变 Skill 发布版本';
COMMENT ON COLUMN public.skill_install_operation.input_json IS '受理时固定的命令输入';
COMMENT ON COLUMN public.skill_install_operation.result_json IS '已提交的结构化执行或审批结果';
COMMENT ON COLUMN public.skill_install_operation.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.skill_install_operation.epoch IS '操作租约的 fencing epoch，旧执行者不得回写';
COMMENT ON COLUMN public.skill_install_operation.lease_until IS '当前认领租约过期时间';
COMMENT ON COLUMN public.skill_install_operation.error_code IS '稳定错误代码，不含堆栈或凭据';
COMMENT ON COLUMN public.skill_install_operation.error_detail IS '已脱敏的公开错误说明';
COMMENT ON COLUMN public.skill_install_operation.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.skill_install_operation.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON COLUMN public.skill_install_operation.cleanup_json IS '失败安装需清理的已创建资产引用';
COMMENT ON CONSTRAINT skill_install_operation_cleanup_json_check ON public.skill_install_operation IS '数据有效性约束：CHECK ((jsonb_typeof(cleanup_json) = ''array''::text))';
COMMENT ON CONSTRAINT skill_install_operation_epoch_check ON public.skill_install_operation IS '数据有效性约束：CHECK ((epoch >= 0))';
COMMENT ON CONSTRAINT skill_install_operation_input_json_check ON public.skill_install_operation IS '数据有效性约束：CHECK (((jsonb_typeof(input_json) = ''object''::text) AND (input_json @> ''{"schemaVersion": 1}''::jsonb)))';
COMMENT ON CONSTRAINT skill_install_operation_lease_check ON public.skill_install_operation IS '数据有效性约束：CHECK (((status IN (''PREPARING'', ''CLEANING'')) = (lease_until IS NOT NULL)))';
COMMENT ON CONSTRAINT skill_install_operation_status_check ON public.skill_install_operation IS '数据有效性约束：CHECK ((status IN (''ACCEPTED'', ''PREPARING'', ''SUCCEEDED'', ''FAILED'', ''CLEANING'')))';
COMMENT ON CONSTRAINT skill_install_operation_owner_id_project_id_skill_version_i_key ON public.skill_install_operation IS '唯一约束：禁止作用域内重复记录  (owner_id, project_id, skill_version_id)';
COMMENT ON CONSTRAINT skill_install_operation_pkey ON public.skill_install_operation IS '主键：唯一标识项目内固定 Skill 版本的安装操作、租约、结果和失败清理的记录  (id)';
COMMENT ON CONSTRAINT skill_install_operation_owner_id_fkey ON public.skill_install_operation IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON CONSTRAINT skill_install_operation_owner_id_skill_id_skill_version_id_fkey ON public.skill_install_operation IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id, skill_id, skill_version_id) REFERENCES public.skill_version(owner_id, skill_id, id)';
COMMENT ON CONSTRAINT skill_install_operation_project_id_fkey ON public.skill_install_operation IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE';
COMMENT ON INDEX public.skill_install_operation_pkey IS '支撑主键 skill_install_operation.skill_install_operation_pkey';
COMMENT ON INDEX public.skill_install_operation_owner_id_project_id_skill_version_i_key IS '支撑唯一约束 skill_install_operation.skill_install_operation_owner_id_project_id_skill_version_i_key';

CREATE INDEX ix_skill_install_claim ON public.skill_install_operation USING btree (status, lease_until, created_at);
COMMENT ON INDEX public.ix_skill_install_claim IS '查询索引：支持项目内固定 Skill 版本的安装操作、租约、结果和失败清理的定位与排序；USING btree (status, lease_until, created_at)';

-- Skill 发布操作、素材归档进度、租约及临时引用清理。
CREATE TABLE public.skill_publish_operation (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    skill_id uuid NOT NULL, -- Skill 业务身份
    command_key character varying(200) NOT NULL, -- 用户命令幂等键
    payload_hash character(64) NOT NULL, -- 规范化命令载荷 SHA-256 摘要
    input_json jsonb NOT NULL, -- 受理时固定的命令输入
    progress_json jsonb DEFAULT '{}'::jsonb NOT NULL, -- 发布素材归档的持久进度
    status character varying(16) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    epoch bigint DEFAULT 0 NOT NULL, -- 操作租约的 fencing epoch，旧执行者不得回写
    lease_until timestamp with time zone, -- 当前认领租约过期时间
    result_version_id uuid, -- 发布完成的不可变 Skill 版本
    error_code character varying(80), -- 稳定错误代码，不含堆栈或凭据
    error_detail character varying(500), -- 已脱敏的公开错误说明
    pins_cleaned boolean DEFAULT false NOT NULL, -- 发布操作临时素材引用已清理
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT skill_publish_operation_check CHECK ((((status)::text = 'ARCHIVING'::text) = (lease_until IS NOT NULL))),
    CONSTRAINT skill_publish_operation_check1 CHECK ((((status)::text = 'SUCCEEDED'::text) = (result_version_id IS NOT NULL))),
    CONSTRAINT skill_publish_operation_command_key_check CHECK ((length(btrim((command_key)::text)) > 0)),
    CONSTRAINT skill_publish_operation_epoch_check CHECK ((epoch >= 0)),
    CONSTRAINT skill_publish_operation_input_json_check CHECK (((jsonb_typeof(input_json) = 'object'::text) AND (input_json @> '{"schemaVersion": 1}'::jsonb))),
    CONSTRAINT skill_publish_operation_payload_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT skill_publish_operation_progress_json_check CHECK ((jsonb_typeof(progress_json) = 'object'::text)),
    CONSTRAINT skill_publish_operation_status_check CHECK ((status IN ('ACCEPTED', 'ARCHIVING', 'SUCCEEDED', 'FAILED'))),
    CONSTRAINT skill_publish_operation_pkey PRIMARY KEY (id),
    CONSTRAINT skill_publish_operation_owner_id_command_key_key UNIQUE (owner_id, command_key),
    CONSTRAINT skill_publish_operation_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id),
    CONSTRAINT skill_publish_operation_owner_id_skill_id_fkey FOREIGN KEY (owner_id, skill_id) REFERENCES public.creative_skill(owner_id, id),
    CONSTRAINT skill_publish_operation_owner_id_skill_id_result_version_i_fkey FOREIGN KEY (owner_id, skill_id, result_version_id) REFERENCES public.skill_version(owner_id, skill_id, id)
);

COMMENT ON TABLE public.skill_publish_operation IS 'Skill 发布操作、素材归档进度、租约及临时引用清理';
COMMENT ON COLUMN public.skill_publish_operation.id IS '记录身份';
COMMENT ON COLUMN public.skill_publish_operation.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.skill_publish_operation.skill_id IS 'Skill 业务身份';
COMMENT ON COLUMN public.skill_publish_operation.command_key IS '用户命令幂等键';
COMMENT ON COLUMN public.skill_publish_operation.payload_hash IS '规范化命令载荷 SHA-256 摘要';
COMMENT ON COLUMN public.skill_publish_operation.input_json IS '受理时固定的命令输入';
COMMENT ON COLUMN public.skill_publish_operation.progress_json IS '发布素材归档的持久进度';
COMMENT ON COLUMN public.skill_publish_operation.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.skill_publish_operation.epoch IS '操作租约的 fencing epoch，旧执行者不得回写';
COMMENT ON COLUMN public.skill_publish_operation.lease_until IS '当前认领租约过期时间';
COMMENT ON COLUMN public.skill_publish_operation.result_version_id IS '发布完成的不可变 Skill 版本';
COMMENT ON COLUMN public.skill_publish_operation.error_code IS '稳定错误代码，不含堆栈或凭据';
COMMENT ON COLUMN public.skill_publish_operation.error_detail IS '已脱敏的公开错误说明';
COMMENT ON COLUMN public.skill_publish_operation.pins_cleaned IS '发布操作临时素材引用已清理';
COMMENT ON COLUMN public.skill_publish_operation.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.skill_publish_operation.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT skill_publish_operation_check ON public.skill_publish_operation IS '数据有效性约束：CHECK ((((status)::text = ''ARCHIVING''::text) = (lease_until IS NOT NULL)))';
COMMENT ON CONSTRAINT skill_publish_operation_check1 ON public.skill_publish_operation IS '数据有效性约束：CHECK ((((status)::text = ''SUCCEEDED''::text) = (result_version_id IS NOT NULL)))';
COMMENT ON CONSTRAINT skill_publish_operation_command_key_check ON public.skill_publish_operation IS '数据有效性约束：CHECK ((length(btrim((command_key)::text)) > 0))';
COMMENT ON CONSTRAINT skill_publish_operation_epoch_check ON public.skill_publish_operation IS '数据有效性约束：CHECK ((epoch >= 0))';
COMMENT ON CONSTRAINT skill_publish_operation_input_json_check ON public.skill_publish_operation IS '数据有效性约束：CHECK (((jsonb_typeof(input_json) = ''object''::text) AND (input_json @> ''{"schemaVersion": 1}''::jsonb)))';
COMMENT ON CONSTRAINT skill_publish_operation_payload_hash_check ON public.skill_publish_operation IS '数据有效性约束：CHECK ((payload_hash ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT skill_publish_operation_progress_json_check ON public.skill_publish_operation IS '数据有效性约束：CHECK ((jsonb_typeof(progress_json) = ''object''::text))';
COMMENT ON CONSTRAINT skill_publish_operation_status_check ON public.skill_publish_operation IS '数据有效性约束：CHECK ((status IN (''ACCEPTED'', ''ARCHIVING'', ''SUCCEEDED'', ''FAILED'')))';
COMMENT ON CONSTRAINT skill_publish_operation_owner_id_command_key_key ON public.skill_publish_operation IS '唯一约束：禁止作用域内重复记录  (owner_id, command_key)';
COMMENT ON CONSTRAINT skill_publish_operation_pkey ON public.skill_publish_operation IS '主键：唯一标识Skill 发布操作、素材归档进度、租约及临时引用清理的记录  (id)';
COMMENT ON CONSTRAINT skill_publish_operation_owner_id_fkey ON public.skill_publish_operation IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON CONSTRAINT skill_publish_operation_owner_id_skill_id_fkey ON public.skill_publish_operation IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id, skill_id) REFERENCES public.creative_skill(owner_id, id)';
COMMENT ON CONSTRAINT skill_publish_operation_owner_id_skill_id_result_version_i_fkey ON public.skill_publish_operation IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id, skill_id, result_version_id) REFERENCES public.skill_version(owner_id, skill_id, id)';
COMMENT ON INDEX public.skill_publish_operation_pkey IS '支撑主键 skill_publish_operation.skill_publish_operation_pkey';
COMMENT ON INDEX public.skill_publish_operation_owner_id_command_key_key IS '支撑唯一约束 skill_publish_operation.skill_publish_operation_owner_id_command_key_key';

CREATE INDEX ix_skill_publish_claim ON public.skill_publish_operation USING btree (status, lease_until, created_at);
COMMENT ON INDEX public.ix_skill_publish_claim IS '查询索引：支持Skill 发布操作、素材归档进度、租约及临时引用清理的定位与排序；USING btree (status, lease_until, created_at)';

-- Agent 显式输入：固定引用不可变产物版本。
CREATE TABLE public.agent_binding (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    agent_instance_id uuid NOT NULL, -- Agent 卡片配置身份
    artifact_id uuid NOT NULL, -- 业务产物身份
    selected_version_id uuid NOT NULL, -- 显式选择的不可变产物版本
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT agent_binding_pkey PRIMARY KEY (id),
    CONSTRAINT uq_agent_binding_artifact UNIQUE (agent_instance_id, artifact_id),
    CONSTRAINT fk_agent_binding_agent FOREIGN KEY (project_id, agent_instance_id) REFERENCES public.agent_instance(project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_binding_artifact FOREIGN KEY (project_id, artifact_id) REFERENCES public.artifact(project_id, id),
    CONSTRAINT fk_agent_binding_version FOREIGN KEY (project_id, artifact_id, selected_version_id) REFERENCES public.artifact_version(project_id, artifact_id, id)
);

COMMENT ON TABLE public.agent_binding IS 'Agent 显式输入：固定引用不可变产物版本';
COMMENT ON COLUMN public.agent_binding.id IS '记录身份';
COMMENT ON COLUMN public.agent_binding.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.agent_binding.agent_instance_id IS 'Agent 卡片配置身份';
COMMENT ON COLUMN public.agent_binding.artifact_id IS '业务产物身份';
COMMENT ON COLUMN public.agent_binding.selected_version_id IS '显式选择的不可变产物版本';
COMMENT ON COLUMN public.agent_binding.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT agent_binding_pkey ON public.agent_binding IS '主键：唯一标识Agent 显式输入：固定引用不可变产物版本的记录  (id)';
COMMENT ON CONSTRAINT uq_agent_binding_artifact ON public.agent_binding IS '唯一约束：禁止作用域内重复记录  (agent_instance_id, artifact_id)';
COMMENT ON CONSTRAINT fk_agent_binding_agent ON public.agent_binding IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, agent_instance_id) REFERENCES public.agent_instance(project_id, id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT fk_agent_binding_artifact ON public.agent_binding IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, artifact_id) REFERENCES public.artifact(project_id, id)';
COMMENT ON CONSTRAINT fk_agent_binding_version ON public.agent_binding IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, artifact_id, selected_version_id) REFERENCES public.artifact_version(project_id, artifact_id, id)';
COMMENT ON INDEX public.agent_binding_pkey IS '支撑主键 agent_binding.agent_binding_pkey';
COMMENT ON INDEX public.uq_agent_binding_artifact IS '支撑唯一约束 agent_binding.uq_agent_binding_artifact';

CREATE INDEX ix_agent_binding_agent ON public.agent_binding USING btree (project_id, agent_instance_id);
COMMENT ON INDEX public.ix_agent_binding_agent IS '查询索引：支持Agent 显式输入：固定引用不可变产物版本的定位与排序；USING btree (project_id, agent_instance_id)';

-- 单次 Agent 指令的持久执行状态及不可变上下文、策略快照。
CREATE TABLE public.agent_run (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    agent_instance_id uuid NOT NULL, -- Agent 卡片配置身份
    user_id uuid NOT NULL, -- 发起执行的可信用户身份
    status character varying(40) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    instruction text NOT NULL, -- 用户指令或 Agent 系统指令
    context_snapshot_json jsonb NOT NULL, -- 受理时固定的授权上下文快照
    policy_snapshot_json jsonb NOT NULL, -- 受理时固定的工具、额度和执行策略快照
    profile_version integer NOT NULL, -- Agent 配置格式版本
    next_step_index integer DEFAULT 0 NOT NULL, -- 下一次模型回合序号
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    completed_at timestamp with time zone, -- 终态完成时间；未完成时为空
    conversation_id uuid NOT NULL, -- 所属持久对话
    conversation_turn bigint NOT NULL, -- 对话内单调递增的用户轮次
    CONSTRAINT ck_agent_run_completion CHECK ((((status IN ('CANCELED', 'FAILED', 'SUCCEEDED')) AND (completed_at IS NOT NULL)) OR ((status NOT IN ('CANCELED', 'FAILED', 'SUCCEEDED')) AND (completed_at IS NULL)))),
    CONSTRAINT ck_agent_run_instruction_not_blank CHECK ((length(btrim(instruction)) > 0)),
    CONSTRAINT ck_agent_run_next_step_non_negative CHECK ((next_step_index >= 0)),
    CONSTRAINT ck_agent_run_profile_version_positive CHECK ((profile_version > 0)),
    CONSTRAINT ck_agent_run_status CHECK ((status IN ('QUEUED', 'RUNNING', 'WAITING_TASKS', 'BLOCKED', 'CANCEL_REQUESTED', 'CANCELED', 'FAILED', 'SUCCEEDED'))),
    CONSTRAINT ck_agent_run_version_non_negative CHECK ((version >= 0)),
    CONSTRAINT ck_run_conversation_turn CHECK ((conversation_turn > 0)),
    CONSTRAINT agent_run_pkey PRIMARY KEY (id),
    CONSTRAINT uq_agent_run_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_run_conversation_turn UNIQUE (conversation_id, conversation_turn),
    CONSTRAINT fk_agent_run_agent FOREIGN KEY (project_id, agent_instance_id) REFERENCES public.agent_instance(project_id, id),
    CONSTRAINT fk_agent_run_project FOREIGN KEY (project_id) REFERENCES public.project(id),
    CONSTRAINT fk_agent_run_user FOREIGN KEY (user_id) REFERENCES public.app_user(id),
    CONSTRAINT fk_run_conversation FOREIGN KEY (project_id, agent_instance_id, conversation_id) REFERENCES public.agent_conversation(project_id, agent_instance_id, id)
);

COMMENT ON TABLE public.agent_run IS '单次 Agent 指令的持久执行状态及不可变上下文、策略快照';
COMMENT ON COLUMN public.agent_run.id IS '记录身份';
COMMENT ON COLUMN public.agent_run.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.agent_run.agent_instance_id IS 'Agent 卡片配置身份';
COMMENT ON COLUMN public.agent_run.user_id IS '发起执行的可信用户身份';
COMMENT ON COLUMN public.agent_run.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.agent_run.instruction IS '用户指令或 Agent 系统指令';
COMMENT ON COLUMN public.agent_run.context_snapshot_json IS '受理时固定的授权上下文快照';
COMMENT ON COLUMN public.agent_run.policy_snapshot_json IS '受理时固定的工具、额度和执行策略快照';
COMMENT ON COLUMN public.agent_run.profile_version IS 'Agent 配置格式版本';
COMMENT ON COLUMN public.agent_run.next_step_index IS '下一次模型回合序号';
COMMENT ON COLUMN public.agent_run.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.agent_run.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.agent_run.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON COLUMN public.agent_run.completed_at IS '终态完成时间；未完成时为空';
COMMENT ON COLUMN public.agent_run.conversation_id IS '所属持久对话';
COMMENT ON COLUMN public.agent_run.conversation_turn IS '对话内单调递增的用户轮次';
COMMENT ON CONSTRAINT ck_agent_run_completion ON public.agent_run IS '数据有效性约束：CHECK ((((status IN (''CANCELED'', ''FAILED'', ''SUCCEEDED'')) AND (completed_at IS NOT NULL)) OR ((status NOT IN (''CANCELED'', ''FAILED'', ''SUCCEEDED'')) AND (completed_at IS NULL))))';
COMMENT ON CONSTRAINT ck_agent_run_instruction_not_blank ON public.agent_run IS '数据有效性约束：CHECK ((length(btrim(instruction)) > 0))';
COMMENT ON CONSTRAINT ck_agent_run_next_step_non_negative ON public.agent_run IS '数据有效性约束：CHECK ((next_step_index >= 0))';
COMMENT ON CONSTRAINT ck_agent_run_profile_version_positive ON public.agent_run IS '数据有效性约束：CHECK ((profile_version > 0))';
COMMENT ON CONSTRAINT ck_agent_run_status ON public.agent_run IS '数据有效性约束：CHECK ((status IN (''QUEUED'', ''RUNNING'', ''WAITING_TASKS'', ''BLOCKED'', ''CANCEL_REQUESTED'', ''CANCELED'', ''FAILED'', ''SUCCEEDED'')))';
COMMENT ON CONSTRAINT ck_agent_run_version_non_negative ON public.agent_run IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT ck_run_conversation_turn ON public.agent_run IS '数据有效性约束：CHECK ((conversation_turn > 0))';
COMMENT ON CONSTRAINT agent_run_pkey ON public.agent_run IS '主键：唯一标识单次 Agent 指令的持久执行状态及不可变上下文、策略快照的记录  (id)';
COMMENT ON CONSTRAINT uq_agent_run_project_id ON public.agent_run IS '唯一约束：禁止作用域内重复记录  (project_id, id)';
COMMENT ON CONSTRAINT uq_run_conversation_turn ON public.agent_run IS '唯一约束：禁止作用域内重复记录  (conversation_id, conversation_turn)';
COMMENT ON CONSTRAINT fk_agent_run_agent ON public.agent_run IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, agent_instance_id) REFERENCES public.agent_instance(project_id, id)';
COMMENT ON CONSTRAINT fk_agent_run_project ON public.agent_run IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id)';
COMMENT ON CONSTRAINT fk_agent_run_user ON public.agent_run IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (user_id) REFERENCES public.app_user(id)';
COMMENT ON CONSTRAINT fk_run_conversation ON public.agent_run IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, agent_instance_id, conversation_id) REFERENCES public.agent_conversation(project_id, agent_instance_id, id)';
COMMENT ON INDEX public.agent_run_pkey IS '支撑主键 agent_run.agent_run_pkey';
COMMENT ON INDEX public.uq_agent_run_project_id IS '支撑唯一约束 agent_run.uq_agent_run_project_id';
COMMENT ON INDEX public.uq_run_conversation_turn IS '支撑唯一约束 agent_run.uq_run_conversation_turn';

CREATE INDEX ix_agent_run_project_created ON public.agent_run USING btree (project_id, created_at DESC, id DESC);
COMMENT ON INDEX public.ix_agent_run_project_created IS '查询索引：支持单次 Agent 指令的持久执行状态及不可变上下文、策略快照的定位与排序；USING btree (project_id, created_at DESC, id DESC)';

CREATE INDEX ix_agent_run_status ON public.agent_run USING btree (status, updated_at) WHERE (status NOT IN ('CANCELED', 'FAILED', 'SUCCEEDED'));
COMMENT ON INDEX public.ix_agent_run_status IS '查询索引：支持单次 Agent 指令的持久执行状态及不可变上下文、策略快照的定位与排序；USING btree (status, updated_at) WHERE (status NOT IN (''CANCELED'', ''FAILED'', ''SUCCEEDED''))';


-- 不可变版本之间的精确输入引用及顺序。
CREATE TABLE public.artifact_version_reference (
    source_version_id uuid NOT NULL, -- 引用来源或导入生成的不可变版本
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    target_version_id uuid NOT NULL, -- 引用指向的不可变目标版本
    reference_role character varying(64) NOT NULL, -- 精确版本引用承担的输入角色
    reference_order integer NOT NULL, -- 同角色引用的稳定顺序
    CONSTRAINT ck_artifact_reference_order_non_negative CHECK ((reference_order >= 0)),
    CONSTRAINT artifact_version_reference_pkey PRIMARY KEY (source_version_id, reference_role, reference_order),
    CONSTRAINT fk_artifact_reference_source FOREIGN KEY (project_id, source_version_id) REFERENCES public.artifact_version(project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_artifact_reference_target FOREIGN KEY (project_id, target_version_id) REFERENCES public.artifact_version(project_id, id)
);

COMMENT ON TABLE public.artifact_version_reference IS '不可变版本之间的精确输入引用及顺序';
COMMENT ON COLUMN public.artifact_version_reference.source_version_id IS '引用来源或导入生成的不可变版本';
COMMENT ON COLUMN public.artifact_version_reference.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.artifact_version_reference.target_version_id IS '引用指向的不可变目标版本';
COMMENT ON COLUMN public.artifact_version_reference.reference_role IS '精确版本引用承担的输入角色';
COMMENT ON COLUMN public.artifact_version_reference.reference_order IS '同角色引用的稳定顺序';
COMMENT ON CONSTRAINT ck_artifact_reference_order_non_negative ON public.artifact_version_reference IS '数据有效性约束：CHECK ((reference_order >= 0))';
COMMENT ON CONSTRAINT artifact_version_reference_pkey ON public.artifact_version_reference IS '主键：唯一标识不可变版本之间的精确输入引用及顺序的记录  (source_version_id, reference_role, reference_order)';
COMMENT ON CONSTRAINT fk_artifact_reference_source ON public.artifact_version_reference IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, source_version_id) REFERENCES public.artifact_version(project_id, id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT fk_artifact_reference_target ON public.artifact_version_reference IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, target_version_id) REFERENCES public.artifact_version(project_id, id)';
COMMENT ON INDEX public.artifact_version_reference_pkey IS '支撑主键 artifact_version_reference.artifact_version_reference_pkey';

CREATE INDEX ix_artifact_reference_target ON public.artifact_version_reference USING btree (project_id, target_version_id);
COMMENT ON INDEX public.ix_artifact_reference_target IS '查询索引：支持不可变版本之间的精确输入引用及顺序的定位与排序；USING btree (project_id, target_version_id)';

-- 画布空间卡片及卡片独立的标题、版本选择与内容选择 epoch。
CREATE TABLE public.canvas_item (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    subject_type character varying(32) NOT NULL, -- 卡片承载 Agent 或 Artifact
    subject_id uuid NOT NULL, -- 卡片业务对象身份
    artifact_id uuid, -- 业务产物身份
    x numeric(14,3) NOT NULL, -- 画布水平坐标
    y numeric(14,3) NOT NULL, -- 画布垂直坐标
    width numeric(14,3) NOT NULL, -- 媒体像素宽度或画布卡片宽度
    height numeric(14,3) NOT NULL, -- 媒体像素高度或画布卡片高度
    z_index integer NOT NULL, -- 画布层叠顺序
    group_id uuid, -- 卡片所属画布分组
    locked boolean DEFAULT false NOT NULL, -- 是否禁止交互修改卡片布局
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    agent_instance_id uuid, -- Agent 卡片配置身份
    title character varying(160) NOT NULL, -- Per-card display title initialized from its subject and edited independently afterward.
    selected_version_id uuid, -- Version displayed by this card. Media cards own this independently of the Artifact default.
    media_selection_epoch bigint DEFAULT 0 NOT NULL, -- Content selection revision, independent of layout CAS; prevents late tasks from replacing user selections.
    CONSTRAINT ck_canvas_item_geometry CHECK (
        x BETWEEN -1000000 AND 1000000
        AND y BETWEEN -1000000 AND 1000000
        AND width BETWEEN 120 AND 2000
        AND height BETWEEN 80 AND 2000
        AND z_index BETWEEN -1000 AND 1000
    ),
    CONSTRAINT ck_canvas_item_selected_version_subject CHECK ((((subject_type)::text = 'ARTIFACT'::text) OR (selected_version_id IS NULL))),
    CONSTRAINT ck_canvas_item_subject_mapping CHECK (((((subject_type)::text = 'ARTIFACT'::text) AND (artifact_id = subject_id) AND (agent_instance_id IS NULL)) OR (((subject_type)::text = 'AGENT'::text) AND (artifact_id IS NULL) AND (agent_instance_id = subject_id)))),
    CONSTRAINT ck_canvas_item_subject_type CHECK ((subject_type IN ('ARTIFACT', 'AGENT'))),
    CONSTRAINT ck_canvas_item_title CHECK (((length(btrim((title)::text)) >= 1) AND (length(btrim((title)::text)) <= 160))),
    CONSTRAINT ck_canvas_item_version_non_negative CHECK ((version >= 0)),
    CONSTRAINT ck_canvas_media_selection_epoch CHECK ((media_selection_epoch >= 0)),
    CONSTRAINT canvas_item_pkey PRIMARY KEY (id),
    CONSTRAINT uq_canvas_item_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_canvas_item_agent FOREIGN KEY (project_id, agent_instance_id) REFERENCES public.agent_instance(project_id, id),
    CONSTRAINT fk_canvas_item_artifact FOREIGN KEY (project_id, artifact_id) REFERENCES public.artifact(project_id, id),
    CONSTRAINT fk_canvas_item_project FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE,
    CONSTRAINT fk_canvas_item_selected_version FOREIGN KEY (artifact_id, selected_version_id) REFERENCES public.artifact_version(artifact_id, id)
);

COMMENT ON TABLE public.canvas_item IS '画布空间卡片及卡片独立的标题、版本选择与内容选择 epoch';
COMMENT ON COLUMN public.canvas_item.id IS '记录身份';
COMMENT ON COLUMN public.canvas_item.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.canvas_item.subject_type IS '卡片承载 Agent 或 Artifact';
COMMENT ON COLUMN public.canvas_item.subject_id IS '卡片业务对象身份';
COMMENT ON COLUMN public.canvas_item.artifact_id IS '业务产物身份';
COMMENT ON COLUMN public.canvas_item.x IS '画布水平坐标';
COMMENT ON COLUMN public.canvas_item.y IS '画布垂直坐标';
COMMENT ON COLUMN public.canvas_item.width IS '媒体像素宽度或画布卡片宽度';
COMMENT ON COLUMN public.canvas_item.height IS '媒体像素高度或画布卡片高度';
COMMENT ON COLUMN public.canvas_item.z_index IS '画布层叠顺序';
COMMENT ON COLUMN public.canvas_item.group_id IS '卡片所属画布分组';
COMMENT ON COLUMN public.canvas_item.locked IS '是否禁止交互修改卡片布局';
COMMENT ON COLUMN public.canvas_item.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.canvas_item.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.canvas_item.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON COLUMN public.canvas_item.agent_instance_id IS 'Agent 卡片配置身份';
COMMENT ON COLUMN public.canvas_item.title IS 'Per-card display title initialized from its subject and edited independently afterward.';
COMMENT ON COLUMN public.canvas_item.selected_version_id IS 'Version displayed by this card. Media cards own this independently of the Artifact default.';
COMMENT ON COLUMN public.canvas_item.media_selection_epoch IS 'Content selection revision, independent of layout CAS; prevents late tasks from replacing user selections.';
COMMENT ON CONSTRAINT ck_canvas_item_geometry ON public.canvas_item IS '数据有效性约束：CHECK ( x BETWEEN -1000000 AND 1000000 AND y BETWEEN -1000000 AND 1000000 AND width BETWEEN 120 AND 2000 AND height BETWEEN 80 AND 2000 AND z_index BETWEEN -1000 AND 1000 )';
COMMENT ON CONSTRAINT ck_canvas_item_selected_version_subject ON public.canvas_item IS '数据有效性约束：CHECK ((((subject_type)::text = ''ARTIFACT''::text) OR (selected_version_id IS NULL)))';
COMMENT ON CONSTRAINT ck_canvas_item_subject_mapping ON public.canvas_item IS '数据有效性约束：CHECK (((((subject_type)::text = ''ARTIFACT''::text) AND (artifact_id = subject_id) AND (agent_instance_id IS NULL)) OR (((subject_type)::text = ''AGENT''::text) AND (artifact_id IS NULL) AND (agent_instance_id = subject_id))))';
COMMENT ON CONSTRAINT ck_canvas_item_subject_type ON public.canvas_item IS '数据有效性约束：CHECK ((subject_type IN (''ARTIFACT'', ''AGENT'')))';
COMMENT ON CONSTRAINT ck_canvas_item_title ON public.canvas_item IS '数据有效性约束：CHECK (((length(btrim((title)::text)) >= 1) AND (length(btrim((title)::text)) <= 160)))';
COMMENT ON CONSTRAINT ck_canvas_item_version_non_negative ON public.canvas_item IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT ck_canvas_media_selection_epoch ON public.canvas_item IS '数据有效性约束：CHECK ((media_selection_epoch >= 0))';
COMMENT ON CONSTRAINT canvas_item_pkey ON public.canvas_item IS '主键：唯一标识画布空间卡片及卡片独立的标题、版本选择与内容选择 epoch的记录  (id)';
COMMENT ON CONSTRAINT uq_canvas_item_project_id ON public.canvas_item IS '唯一约束：禁止作用域内重复记录  (project_id, id)';
COMMENT ON CONSTRAINT fk_canvas_item_agent ON public.canvas_item IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, agent_instance_id) REFERENCES public.agent_instance(project_id, id)';
COMMENT ON CONSTRAINT fk_canvas_item_artifact ON public.canvas_item IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, artifact_id) REFERENCES public.artifact(project_id, id)';
COMMENT ON CONSTRAINT fk_canvas_item_project ON public.canvas_item IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT fk_canvas_item_selected_version ON public.canvas_item IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (artifact_id, selected_version_id) REFERENCES public.artifact_version(artifact_id, id)';
COMMENT ON INDEX public.canvas_item_pkey IS '支撑主键 canvas_item.canvas_item_pkey';
COMMENT ON INDEX public.uq_canvas_item_project_id IS '支撑唯一约束 canvas_item.uq_canvas_item_project_id';

CREATE INDEX ix_canvas_item_project ON public.canvas_item USING btree (project_id, z_index, id);
COMMENT ON INDEX public.ix_canvas_item_project IS '查询索引：支持画布空间卡片及卡片独立的标题、版本选择与内容选择 epoch的定位与排序；USING btree (project_id, z_index, id)';

CREATE INDEX ix_canvas_item_subject ON public.canvas_item USING btree (project_id, subject_type, subject_id);
COMMENT ON INDEX public.ix_canvas_item_subject IS '查询索引：支持画布空间卡片及卡片独立的标题、版本选择与内容选择 epoch的定位与排序；USING btree (project_id, subject_type, subject_id)';

-- 素材库导入到项目的版本及原条目来源审计。
CREATE TABLE public.library_import (
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    version_id uuid NOT NULL, -- 导入生成的不可变产物版本
    entry_id uuid NOT NULL, -- 个人素材库来源条目
    source_json jsonb NOT NULL, -- 固定导入来源审计信息
    CONSTRAINT library_import_source_json_check CHECK ((jsonb_typeof(source_json) = 'object'::text)),
    CONSTRAINT library_import_source_schema CHECK (((source_json ? 'schemaVersion'::text) AND ((source_json ->> 'schemaVersion'::text) = '1'::text))),
    CONSTRAINT library_import_pkey PRIMARY KEY (project_id, version_id),
    CONSTRAINT library_import_project_id_version_id_fkey FOREIGN KEY (project_id, version_id) REFERENCES public.artifact_version(project_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE public.library_import IS '素材库导入到项目的版本及原条目来源审计';
COMMENT ON COLUMN public.library_import.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.library_import.version_id IS '导入生成的不可变产物版本';
COMMENT ON COLUMN public.library_import.entry_id IS '个人素材库来源条目';
COMMENT ON COLUMN public.library_import.source_json IS '固定导入来源审计信息';
COMMENT ON CONSTRAINT library_import_source_json_check ON public.library_import IS '数据有效性约束：CHECK ((jsonb_typeof(source_json) = ''object''::text))';
COMMENT ON CONSTRAINT library_import_source_schema ON public.library_import IS '数据有效性约束：CHECK (((source_json ? ''schemaVersion''::text) AND ((source_json ->> ''schemaVersion''::text) = ''1''::text)))';
COMMENT ON CONSTRAINT library_import_pkey ON public.library_import IS '主键：唯一标识素材库导入到项目的版本及原条目来源审计的记录  (project_id, version_id)';
COMMENT ON CONSTRAINT library_import_project_id_version_id_fkey ON public.library_import IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, version_id) REFERENCES public.artifact_version(project_id, id) ON DELETE CASCADE';
COMMENT ON INDEX public.library_import_pkey IS '支撑主键 library_import.library_import_pkey';

-- 模板图片导入到项目的不可变版本及模板来源审计。
CREATE TABLE public.media_template_import_image (
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    version_id uuid NOT NULL, -- 导入生成的不可变产物版本
    template_id uuid NOT NULL, -- 媒体模板身份
    template_version bigint NOT NULL, -- 导入时固定的模板版本
    template_name character varying(160) NOT NULL, -- 导入时固定的模板名称
    CONSTRAINT media_template_import_image_template_version_check CHECK ((template_version >= 0)),
    CONSTRAINT media_template_import_image_pkey PRIMARY KEY (project_id, version_id),
    CONSTRAINT media_template_import_image_project_id_version_id_fkey FOREIGN KEY (project_id, version_id) REFERENCES public.artifact_version(project_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE public.media_template_import_image IS '模板图片导入到项目的不可变版本及模板来源审计';
COMMENT ON COLUMN public.media_template_import_image.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.media_template_import_image.version_id IS '导入生成的不可变产物版本';
COMMENT ON COLUMN public.media_template_import_image.template_id IS '媒体模板身份';
COMMENT ON COLUMN public.media_template_import_image.template_version IS '导入时固定的模板版本';
COMMENT ON COLUMN public.media_template_import_image.template_name IS '导入时固定的模板名称';
COMMENT ON CONSTRAINT media_template_import_image_template_version_check ON public.media_template_import_image IS '数据有效性约束：CHECK ((template_version >= 0))';
COMMENT ON CONSTRAINT media_template_import_image_pkey ON public.media_template_import_image IS '主键：唯一标识模板图片导入到项目的不可变版本及模板来源审计的记录  (project_id, version_id)';
COMMENT ON CONSTRAINT media_template_import_image_project_id_version_id_fkey ON public.media_template_import_image IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, version_id) REFERENCES public.artifact_version(project_id, id) ON DELETE CASCADE';
COMMENT ON INDEX public.media_template_import_image_pkey IS '支撑主键 media_template_import_image.media_template_import_image_pkey';

-- Skill 安装命令幂等账本；多个命令可引用同一安装操作。
CREATE TABLE public.skill_install_command (
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    command_key character varying(200) NOT NULL, -- 用户命令幂等键
    payload_hash character(64) NOT NULL, -- 规范化命令载荷 SHA-256 摘要
    operation_id uuid NOT NULL, -- 审批批次或安装操作的稳定身份
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT skill_install_command_command_key_check CHECK ((length(btrim((command_key)::text)) > 0)),
    CONSTRAINT skill_install_command_payload_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT skill_install_command_pkey PRIMARY KEY (owner_id, project_id, command_key),
    CONSTRAINT skill_install_command_operation_id_fkey FOREIGN KEY (operation_id) REFERENCES public.skill_install_operation(id) ON DELETE CASCADE,
    CONSTRAINT skill_install_command_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.app_user(id),
    CONSTRAINT skill_install_command_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.skill_install_command IS 'Skill 安装命令幂等账本；多个命令可引用同一安装操作';
COMMENT ON COLUMN public.skill_install_command.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.skill_install_command.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.skill_install_command.command_key IS '用户命令幂等键';
COMMENT ON COLUMN public.skill_install_command.payload_hash IS '规范化命令载荷 SHA-256 摘要';
COMMENT ON COLUMN public.skill_install_command.operation_id IS '审批批次或安装操作的稳定身份';
COMMENT ON COLUMN public.skill_install_command.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT skill_install_command_command_key_check ON public.skill_install_command IS '数据有效性约束：CHECK ((length(btrim((command_key)::text)) > 0))';
COMMENT ON CONSTRAINT skill_install_command_payload_hash_check ON public.skill_install_command IS '数据有效性约束：CHECK ((payload_hash ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT skill_install_command_pkey ON public.skill_install_command IS '主键：唯一标识Skill 安装命令幂等账本的记录  (owner_id, project_id, command_key)';
COMMENT ON CONSTRAINT skill_install_command_operation_id_fkey ON public.skill_install_command IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (operation_id) REFERENCES public.skill_install_operation(id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT skill_install_command_owner_id_fkey ON public.skill_install_command IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON CONSTRAINT skill_install_command_project_id_fkey ON public.skill_install_command IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE';
COMMENT ON INDEX public.skill_install_command_pkey IS '支撑主键 skill_install_command.skill_install_command_pkey';

-- Agent 固定媒体批次、用户审批决定及原 Run 的结果通知状态。
CREATE TABLE public.agent_media_approval (
    id uuid NOT NULL, -- 记录身份
    owner_id uuid NOT NULL, -- 所属用户及授权作用域
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    run_id uuid NOT NULL, -- 所属 Agent Run；用户直连任务为空
    step_index integer NOT NULL, -- Run 内模型回合序号
    tool_call_id character varying(200) NOT NULL, -- 模型完整响应中的工具调用标识
    operation_id uuid NOT NULL, -- 审批批次或安装操作的稳定身份
    request_json jsonb NOT NULL, -- 固定的模型请求或媒体审批批次
    target_json jsonb NOT NULL, -- 批次受理时固定的目标卡片与版本选择
    task_ids_json jsonb DEFAULT '[]'::jsonb NOT NULL, -- 审批批准后创建的固定任务身份数组
    result_json jsonb, -- 已提交的结构化执行或审批结果
    status character varying(24) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    expires_at timestamp with time zone NOT NULL, -- 记录或审批过期时间
    execution_deadline timestamp with time zone, -- 已批准媒体批次等待结果的截止时间
    decision_key character varying(200), -- 用户审批决定幂等键
    decision_hash character(64), -- 规范化审批决定 SHA-256 摘要
    notification_pending boolean DEFAULT true NOT NULL, -- 该审批结果仍待通知原 Run
    CONSTRAINT ck_agent_media_approval_decision CHECK ((((decision_key IS NULL) AND (decision_hash IS NULL)) OR ((length(btrim((decision_key)::text)) > 0) AND (decision_hash ~ '^[0-9a-f]{64}$'::text)))),
    CONSTRAINT ck_agent_media_approval_execution CHECK ((((status)::text <> 'APPROVED'::text) OR ((execution_deadline IS NOT NULL) AND (jsonb_array_length(task_ids_json) = jsonb_array_length((request_json -> 'outputs'::text)))))),
    CONSTRAINT ck_agent_media_approval_expiry CHECK ((expires_at > created_at)),
    CONSTRAINT ck_agent_media_approval_request CHECK (((jsonb_typeof(request_json) = 'object'::text) AND ((request_json ->> 'schemaVersion'::text) = '1'::text) AND (jsonb_typeof((request_json -> 'outputs'::text)) = 'array'::text) AND ((jsonb_array_length((request_json -> 'outputs'::text)) >= 1) AND (jsonb_array_length((request_json -> 'outputs'::text)) <= 6)))),
    CONSTRAINT ck_agent_media_approval_result CHECK (((result_json IS NULL) OR ((jsonb_typeof(result_json) = 'object'::text) AND ((result_json ->> 'schemaVersion'::text) = '1'::text)))),
    CONSTRAINT ck_agent_media_approval_status CHECK ((status IN ('PENDING', 'APPROVED', 'SUCCEEDED', 'FAILED', 'REJECTED', 'EXPIRED', 'CANCELED'))),
    CONSTRAINT ck_agent_media_approval_targets CHECK (((jsonb_typeof(target_json) = 'object'::text) AND ((target_json ->> 'schemaVersion'::text) = '1'::text) AND (jsonb_typeof((target_json -> 'outputs'::text)) = 'array'::text) AND (jsonb_array_length((target_json -> 'outputs'::text)) = jsonb_array_length((request_json -> 'outputs'::text))))),
    CONSTRAINT ck_agent_media_approval_tasks CHECK ((jsonb_typeof(task_ids_json) = 'array'::text)),
    CONSTRAINT ck_agent_media_approval_tool_call CHECK ((length(btrim((tool_call_id)::text)) > 0)),
    CONSTRAINT ck_agent_media_approval_version CHECK (((version >= 0) AND (step_index >= 0))),
    CONSTRAINT agent_media_approval_pkey PRIMARY KEY (id),
    CONSTRAINT uq_agent_media_approval_project_id UNIQUE (project_id, id),
    CONSTRAINT uq_agent_media_approval_tool UNIQUE (run_id, step_index, tool_call_id),
    CONSTRAINT fk_agent_media_approval_owner FOREIGN KEY (owner_id) REFERENCES public.app_user(id),
    CONSTRAINT fk_agent_media_approval_run FOREIGN KEY (project_id, run_id) REFERENCES public.agent_run(project_id, id)
);

COMMENT ON TABLE public.agent_media_approval IS 'Agent 固定媒体批次、用户审批决定及原 Run 的结果通知状态';
COMMENT ON COLUMN public.agent_media_approval.id IS '记录身份';
COMMENT ON COLUMN public.agent_media_approval.owner_id IS '所属用户及授权作用域';
COMMENT ON COLUMN public.agent_media_approval.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.agent_media_approval.run_id IS '所属 Agent Run；用户直连任务为空';
COMMENT ON COLUMN public.agent_media_approval.step_index IS 'Run 内模型回合序号';
COMMENT ON COLUMN public.agent_media_approval.tool_call_id IS '模型完整响应中的工具调用标识';
COMMENT ON COLUMN public.agent_media_approval.operation_id IS '审批批次或安装操作的稳定身份';
COMMENT ON COLUMN public.agent_media_approval.request_json IS '固定的模型请求或媒体审批批次';
COMMENT ON COLUMN public.agent_media_approval.target_json IS '批次受理时固定的目标卡片与版本选择';
COMMENT ON COLUMN public.agent_media_approval.task_ids_json IS '审批批准后创建的固定任务身份数组';
COMMENT ON COLUMN public.agent_media_approval.result_json IS '已提交的结构化执行或审批结果';
COMMENT ON COLUMN public.agent_media_approval.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.agent_media_approval.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.agent_media_approval.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.agent_media_approval.expires_at IS '记录或审批过期时间';
COMMENT ON COLUMN public.agent_media_approval.execution_deadline IS '已批准媒体批次等待结果的截止时间';
COMMENT ON COLUMN public.agent_media_approval.decision_key IS '用户审批决定幂等键';
COMMENT ON COLUMN public.agent_media_approval.decision_hash IS '规范化审批决定 SHA-256 摘要';
COMMENT ON COLUMN public.agent_media_approval.notification_pending IS '该审批结果仍待通知原 Run';
COMMENT ON CONSTRAINT ck_agent_media_approval_decision ON public.agent_media_approval IS '数据有效性约束：CHECK ((((decision_key IS NULL) AND (decision_hash IS NULL)) OR ((length(btrim((decision_key)::text)) > 0) AND (decision_hash ~ ''^[0-9a-f]{64}$''::text))))';
COMMENT ON CONSTRAINT ck_agent_media_approval_execution ON public.agent_media_approval IS '数据有效性约束：CHECK ((((status)::text <> ''APPROVED''::text) OR ((execution_deadline IS NOT NULL) AND (jsonb_array_length(task_ids_json) = jsonb_array_length((request_json -> ''outputs''::text))))))';
COMMENT ON CONSTRAINT ck_agent_media_approval_expiry ON public.agent_media_approval IS '数据有效性约束：CHECK ((expires_at > created_at))';
COMMENT ON CONSTRAINT ck_agent_media_approval_request ON public.agent_media_approval IS '数据有效性约束：CHECK (((jsonb_typeof(request_json) = ''object''::text) AND ((request_json ->> ''schemaVersion''::text) = ''1''::text) AND (jsonb_typeof((request_json -> ''outputs''::text)) = ''array''::text) AND ((jsonb_array_length((request_json -> ''outputs''::text)) >= 1) AND (jsonb_array_length((request_json -> ''outputs''::text)) <= 6))))';
COMMENT ON CONSTRAINT ck_agent_media_approval_result ON public.agent_media_approval IS '数据有效性约束：CHECK (((result_json IS NULL) OR ((jsonb_typeof(result_json) = ''object''::text) AND ((result_json ->> ''schemaVersion''::text) = ''1''::text))))';
COMMENT ON CONSTRAINT ck_agent_media_approval_status ON public.agent_media_approval IS '数据有效性约束：CHECK ((status IN (''PENDING'', ''APPROVED'', ''SUCCEEDED'', ''FAILED'', ''REJECTED'', ''EXPIRED'', ''CANCELED'')))';
COMMENT ON CONSTRAINT ck_agent_media_approval_targets ON public.agent_media_approval IS '数据有效性约束：CHECK (((jsonb_typeof(target_json) = ''object''::text) AND ((target_json ->> ''schemaVersion''::text) = ''1''::text) AND (jsonb_typeof((target_json -> ''outputs''::text)) = ''array''::text) AND (jsonb_array_length((target_json -> ''outputs''::text)) = jsonb_array_length((request_json -> ''outputs''::text)))))';
COMMENT ON CONSTRAINT ck_agent_media_approval_tasks ON public.agent_media_approval IS '数据有效性约束：CHECK ((jsonb_typeof(task_ids_json) = ''array''::text))';
COMMENT ON CONSTRAINT ck_agent_media_approval_tool_call ON public.agent_media_approval IS '数据有效性约束：CHECK ((length(btrim((tool_call_id)::text)) > 0))';
COMMENT ON CONSTRAINT ck_agent_media_approval_version ON public.agent_media_approval IS '数据有效性约束：CHECK (((version >= 0) AND (step_index >= 0)))';
COMMENT ON CONSTRAINT agent_media_approval_pkey ON public.agent_media_approval IS '主键：唯一标识Agent 固定媒体批次、用户审批决定及原 Run 的结果通知状态的记录  (id)';
COMMENT ON CONSTRAINT uq_agent_media_approval_project_id ON public.agent_media_approval IS '唯一约束：禁止作用域内重复记录  (project_id, id)';
COMMENT ON CONSTRAINT uq_agent_media_approval_tool ON public.agent_media_approval IS '唯一约束：禁止作用域内重复记录  (run_id, step_index, tool_call_id)';
COMMENT ON CONSTRAINT fk_agent_media_approval_owner ON public.agent_media_approval IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (owner_id) REFERENCES public.app_user(id)';
COMMENT ON CONSTRAINT fk_agent_media_approval_run ON public.agent_media_approval IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, run_id) REFERENCES public.agent_run(project_id, id)';
COMMENT ON INDEX public.agent_media_approval_pkey IS '支撑主键 agent_media_approval.agent_media_approval_pkey';
COMMENT ON INDEX public.uq_agent_media_approval_project_id IS '支撑唯一约束 agent_media_approval.uq_agent_media_approval_project_id';
COMMENT ON INDEX public.uq_agent_media_approval_tool IS '支撑唯一约束 agent_media_approval.uq_agent_media_approval_tool';

CREATE INDEX ix_agent_media_approval_outstanding ON public.agent_media_approval USING btree (id) WHERE ((status IN ('PENDING', 'APPROVED')) OR notification_pending);
COMMENT ON INDEX public.ix_agent_media_approval_outstanding IS '查询索引：支持Agent 固定媒体批次、用户审批决定及原 Run 的结果通知状态的定位与排序；USING btree (id) WHERE ((status IN (''PENDING'', ''APPROVED'')) OR notification_pending)';

CREATE INDEX ix_agent_media_approval_run ON public.agent_media_approval USING btree (project_id, run_id, created_at, id);
COMMENT ON INDEX public.ix_agent_media_approval_run IS '查询索引：支持Agent 固定媒体批次、用户审批决定及原 Run 的结果通知状态的定位与排序；USING btree (project_id, run_id, created_at, id)';

CREATE INDEX ix_agent_media_approval_tasks ON public.agent_media_approval USING gin (task_ids_json);
COMMENT ON INDEX public.ix_agent_media_approval_tasks IS '查询索引：支持Agent 固定媒体批次、用户审批决定及原 Run 的结果通知状态的定位与排序；USING gin (task_ids_json)';

-- 画布卡片关系；输入来源和派生线不代表执行依赖。
CREATE TABLE public.canvas_connection (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    source_canvas_item_id uuid NOT NULL, -- 连线起始卡片
    target_canvas_item_id uuid NOT NULL, -- 连线目标卡片
    relation_type character varying(32) NOT NULL, -- MEDIA_INPUT and AGENT_IMAGE_INPUT are editable inputs; MEDIA_DERIVATION is removable media lineage.
    source_artifact_version_id uuid NOT NULL, -- 连线固定的来源版本
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT ck_canvas_connection_distinct_items CHECK ((source_canvas_item_id <> target_canvas_item_id)),
    CONSTRAINT ck_canvas_connection_type CHECK ((relation_type IN ('MEDIA_INPUT', 'AGENT_IMAGE_INPUT', 'MEDIA_DERIVATION'))),
    CONSTRAINT ck_canvas_connection_version CHECK ((version >= 0)),
    CONSTRAINT canvas_connection_pkey PRIMARY KEY (id),
    CONSTRAINT uq_canvas_connection_identity UNIQUE (project_id, source_canvas_item_id, target_canvas_item_id, relation_type),
    CONSTRAINT fk_canvas_connection_source FOREIGN KEY (project_id, source_canvas_item_id) REFERENCES public.canvas_item(project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_canvas_connection_target FOREIGN KEY (project_id, target_canvas_item_id) REFERENCES public.canvas_item(project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_canvas_connection_version FOREIGN KEY (project_id, source_artifact_version_id) REFERENCES public.artifact_version(project_id, id)
);

COMMENT ON TABLE public.canvas_connection IS '画布卡片关系；输入来源和派生线不代表执行依赖';
COMMENT ON COLUMN public.canvas_connection.id IS '记录身份';
COMMENT ON COLUMN public.canvas_connection.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.canvas_connection.source_canvas_item_id IS '连线起始卡片';
COMMENT ON COLUMN public.canvas_connection.target_canvas_item_id IS '连线目标卡片';
COMMENT ON COLUMN public.canvas_connection.relation_type IS 'MEDIA_INPUT and AGENT_IMAGE_INPUT are editable inputs; MEDIA_DERIVATION is removable media lineage.';
COMMENT ON COLUMN public.canvas_connection.source_artifact_version_id IS '连线固定的来源版本';
COMMENT ON COLUMN public.canvas_connection.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.canvas_connection.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.canvas_connection.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT ck_canvas_connection_distinct_items ON public.canvas_connection IS '数据有效性约束：CHECK ((source_canvas_item_id <> target_canvas_item_id))';
COMMENT ON CONSTRAINT ck_canvas_connection_type ON public.canvas_connection IS '数据有效性约束：CHECK ((relation_type IN (''MEDIA_INPUT'', ''AGENT_IMAGE_INPUT'', ''MEDIA_DERIVATION'')))';
COMMENT ON CONSTRAINT ck_canvas_connection_version ON public.canvas_connection IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT canvas_connection_pkey ON public.canvas_connection IS '主键：唯一标识画布卡片关系的记录  (id)';
COMMENT ON CONSTRAINT uq_canvas_connection_identity ON public.canvas_connection IS '唯一约束：禁止作用域内重复记录  (project_id, source_canvas_item_id, target_canvas_item_id, relation_type)';
COMMENT ON CONSTRAINT fk_canvas_connection_source ON public.canvas_connection IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, source_canvas_item_id) REFERENCES public.canvas_item(project_id, id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT fk_canvas_connection_target ON public.canvas_connection IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, target_canvas_item_id) REFERENCES public.canvas_item(project_id, id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT fk_canvas_connection_version ON public.canvas_connection IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, source_artifact_version_id) REFERENCES public.artifact_version(project_id, id)';
COMMENT ON INDEX public.canvas_connection_pkey IS '支撑主键 canvas_connection.canvas_connection_pkey';
COMMENT ON INDEX public.uq_canvas_connection_identity IS '支撑唯一约束 canvas_connection.uq_canvas_connection_identity';

CREATE INDEX ix_canvas_connection_target ON public.canvas_connection USING btree (project_id, target_canvas_item_id);
COMMENT ON INDEX public.ix_canvas_connection_target IS '查询索引：支持画布卡片关系的定位与排序；USING btree (project_id, target_canvas_item_id)';

-- 媒体卡片独占的结果版本历史。
CREATE TABLE public.canvas_item_media_version (
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    canvas_item_id uuid NOT NULL, -- 固定的目标或上下文画布卡片
    artifact_version_id uuid NOT NULL, -- 固定的不可变产物版本
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT canvas_item_media_version_pkey PRIMARY KEY (canvas_item_id, artifact_version_id),
    CONSTRAINT canvas_item_media_version_project_id_artifact_version_id_fkey FOREIGN KEY (project_id, artifact_version_id) REFERENCES public.artifact_version(project_id, id),
    CONSTRAINT canvas_item_media_version_project_id_canvas_item_id_fkey FOREIGN KEY (project_id, canvas_item_id) REFERENCES public.canvas_item(project_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE public.canvas_item_media_version IS '媒体卡片独占的结果版本历史';
COMMENT ON COLUMN public.canvas_item_media_version.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.canvas_item_media_version.canvas_item_id IS '固定的目标或上下文画布卡片';
COMMENT ON COLUMN public.canvas_item_media_version.artifact_version_id IS '固定的不可变产物版本';
COMMENT ON COLUMN public.canvas_item_media_version.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT canvas_item_media_version_pkey ON public.canvas_item_media_version IS '主键：唯一标识媒体卡片独占的结果版本历史的记录  (canvas_item_id, artifact_version_id)';
COMMENT ON CONSTRAINT canvas_item_media_version_project_id_artifact_version_id_fkey ON public.canvas_item_media_version IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, artifact_version_id) REFERENCES public.artifact_version(project_id, id)';
COMMENT ON CONSTRAINT canvas_item_media_version_project_id_canvas_item_id_fkey ON public.canvas_item_media_version IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, canvas_item_id) REFERENCES public.canvas_item(project_id, id) ON DELETE CASCADE';
COMMENT ON INDEX public.canvas_item_media_version_pkey IS '支撑主键 canvas_item_media_version.canvas_item_media_version_pkey';

-- 固定模型配置版本的持久模型回合，保存完整响应后才执行工具。
CREATE TABLE public.llm_turn (
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    run_id uuid NOT NULL, -- 所属 Agent Run；用户直连任务为空
    step_index integer NOT NULL, -- Run 内模型回合序号
    status character varying(20) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    model_config_version integer NOT NULL, -- 本模型回合固定使用的 LLM 配置版本
    request_json jsonb NOT NULL, -- 固定的模型请求或媒体审批批次
    response_json jsonb, -- 已提交的命令响应或完整模型响应
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    responded_at timestamp with time zone, -- 完整响应持久化时间
    CONSTRAINT ck_llm_turn_config_version CHECK ((model_config_version > 0)),
    CONSTRAINT ck_llm_turn_request_object CHECK ((jsonb_typeof(request_json) = 'object'::text)),
    CONSTRAINT ck_llm_turn_response_object CHECK (((response_json IS NULL) OR (jsonb_typeof(response_json) = 'object'::text))),
    CONSTRAINT ck_llm_turn_response_state CHECK (((((status)::text = 'REQUESTED'::text) AND (response_json IS NULL) AND (responded_at IS NULL)) OR (((status)::text = 'RESPONDED'::text) AND (response_json IS NOT NULL) AND (responded_at IS NOT NULL)))),
    CONSTRAINT ck_llm_turn_status CHECK ((status IN ('REQUESTED', 'RESPONDED'))),
    CONSTRAINT ck_llm_turn_step CHECK ((step_index >= 0)),
    CONSTRAINT llm_turn_pkey PRIMARY KEY (run_id, step_index),
    CONSTRAINT fk_llm_turn_run FOREIGN KEY (project_id, run_id) REFERENCES public.agent_run(project_id, id)
);

COMMENT ON TABLE public.llm_turn IS '固定模型配置版本的持久模型回合，保存完整响应后才执行工具';
COMMENT ON COLUMN public.llm_turn.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.llm_turn.run_id IS '所属 Agent Run；用户直连任务为空';
COMMENT ON COLUMN public.llm_turn.step_index IS 'Run 内模型回合序号';
COMMENT ON COLUMN public.llm_turn.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.llm_turn.model_config_version IS '本模型回合固定使用的 LLM 配置版本';
COMMENT ON COLUMN public.llm_turn.request_json IS '固定的模型请求或媒体审批批次';
COMMENT ON COLUMN public.llm_turn.response_json IS '已提交的命令响应或完整模型响应';
COMMENT ON COLUMN public.llm_turn.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.llm_turn.responded_at IS '完整响应持久化时间';
COMMENT ON CONSTRAINT ck_llm_turn_config_version ON public.llm_turn IS '数据有效性约束：CHECK ((model_config_version > 0))';
COMMENT ON CONSTRAINT ck_llm_turn_request_object ON public.llm_turn IS '数据有效性约束：CHECK ((jsonb_typeof(request_json) = ''object''::text))';
COMMENT ON CONSTRAINT ck_llm_turn_response_object ON public.llm_turn IS '数据有效性约束：CHECK (((response_json IS NULL) OR (jsonb_typeof(response_json) = ''object''::text)))';
COMMENT ON CONSTRAINT ck_llm_turn_response_state ON public.llm_turn IS '数据有效性约束：CHECK (((((status)::text = ''REQUESTED''::text) AND (response_json IS NULL) AND (responded_at IS NULL)) OR (((status)::text = ''RESPONDED''::text) AND (response_json IS NOT NULL) AND (responded_at IS NOT NULL))))';
COMMENT ON CONSTRAINT ck_llm_turn_status ON public.llm_turn IS '数据有效性约束：CHECK ((status IN (''REQUESTED'', ''RESPONDED'')))';
COMMENT ON CONSTRAINT ck_llm_turn_step ON public.llm_turn IS '数据有效性约束：CHECK ((step_index >= 0))';
COMMENT ON CONSTRAINT llm_turn_pkey ON public.llm_turn IS '主键：唯一标识固定模型配置版本的持久模型回合，保存完整响应后才执行工具的记录  (run_id, step_index)';
COMMENT ON CONSTRAINT fk_llm_turn_run ON public.llm_turn IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, run_id) REFERENCES public.agent_run(project_id, id)';
COMMENT ON INDEX public.llm_turn_pkey IS '支撑主键 llm_turn.llm_turn_pkey';

CREATE INDEX ix_llm_turn_project_run ON public.llm_turn USING btree (project_id, run_id, step_index);
COMMENT ON INDEX public.ix_llm_turn_project_run IS '查询索引：支持固定模型配置版本的持久模型回合，保存完整响应后才执行工具的定位与排序；USING btree (project_id, run_id, step_index)';

-- 媒体卡片独立草稿、参数、能力、风格和引用提及。
CREATE TABLE public.media_draft (
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    canvas_item_id uuid NOT NULL, -- 固定的目标或上下文画布卡片
    prompt text DEFAULT ''::text NOT NULL, -- 用户媒体生成提示词
    duration_seconds integer, -- 媒体草稿时长，单位秒
    capability_id uuid, -- 固定媒体能力身份
    display_mode character varying(12) DEFAULT 'DRAFT'::character varying NOT NULL, -- 媒体卡片预览显示方式
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    parameters_json jsonb DEFAULT '{}'::jsonb NOT NULL, -- 媒体草稿的结构化参数
    video_input_mode character varying(24), -- 视频输入参考模式
    mentions_json jsonb DEFAULT '[]'::jsonb NOT NULL, -- 草稿内的明确输入引用提及
    style_id uuid, -- Optional image/video visual style; user prompt remains unchanged. Disabled choices remain explicit.
    CONSTRAINT ck_media_draft_display_mode CHECK ((display_mode IN ('DRAFT', 'RESULT'))),
    CONSTRAINT ck_media_draft_duration CHECK (((duration_seconds IS NULL) OR ((duration_seconds >= 1) AND (duration_seconds <= 30)))),
    CONSTRAINT ck_media_draft_mentions_array CHECK ((jsonb_typeof(mentions_json) = 'array'::text)),
    CONSTRAINT ck_media_draft_parameters_object CHECK ((jsonb_typeof(parameters_json) = 'object'::text)),
    CONSTRAINT ck_media_draft_prompt_length CHECK ((length(prompt) <= 20000)),
    CONSTRAINT ck_media_draft_version CHECK ((version >= 0)),
    CONSTRAINT ck_media_draft_video_input_mode CHECK (((video_input_mode IS NULL) OR (video_input_mode IN ('TEXT', 'START_END', 'GENERAL_REFERENCE')))),
    CONSTRAINT media_draft_pkey PRIMARY KEY (project_id, canvas_item_id),
    CONSTRAINT fk_media_draft_canvas_item FOREIGN KEY (project_id, canvas_item_id) REFERENCES public.canvas_item(project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_media_draft_capability FOREIGN KEY (capability_id) REFERENCES public.media_capability(id),
    CONSTRAINT fk_media_draft_style FOREIGN KEY (style_id) REFERENCES public.media_style(id)
);

COMMENT ON TABLE public.media_draft IS '媒体卡片独立草稿、参数、能力、风格和引用提及';
COMMENT ON COLUMN public.media_draft.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.media_draft.canvas_item_id IS '固定的目标或上下文画布卡片';
COMMENT ON COLUMN public.media_draft.prompt IS '用户媒体生成提示词';
COMMENT ON COLUMN public.media_draft.duration_seconds IS '媒体草稿时长，单位秒';
COMMENT ON COLUMN public.media_draft.capability_id IS '固定媒体能力身份';
COMMENT ON COLUMN public.media_draft.display_mode IS '媒体卡片预览显示方式';
COMMENT ON COLUMN public.media_draft.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.media_draft.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.media_draft.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON COLUMN public.media_draft.parameters_json IS '媒体草稿的结构化参数';
COMMENT ON COLUMN public.media_draft.video_input_mode IS '视频输入参考模式';
COMMENT ON COLUMN public.media_draft.mentions_json IS '草稿内的明确输入引用提及';
COMMENT ON COLUMN public.media_draft.style_id IS 'Optional image/video visual style; user prompt remains unchanged. Disabled choices remain explicit.';
COMMENT ON CONSTRAINT ck_media_draft_display_mode ON public.media_draft IS '数据有效性约束：CHECK ((display_mode IN (''DRAFT'', ''RESULT'')))';
COMMENT ON CONSTRAINT ck_media_draft_duration ON public.media_draft IS '数据有效性约束：CHECK (((duration_seconds IS NULL) OR ((duration_seconds >= 1) AND (duration_seconds <= 30))))';
COMMENT ON CONSTRAINT ck_media_draft_mentions_array ON public.media_draft IS '数据有效性约束：CHECK ((jsonb_typeof(mentions_json) = ''array''::text))';
COMMENT ON CONSTRAINT ck_media_draft_parameters_object ON public.media_draft IS '数据有效性约束：CHECK ((jsonb_typeof(parameters_json) = ''object''::text))';
COMMENT ON CONSTRAINT ck_media_draft_prompt_length ON public.media_draft IS '数据有效性约束：CHECK ((length(prompt) <= 20000))';
COMMENT ON CONSTRAINT ck_media_draft_version ON public.media_draft IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT ck_media_draft_video_input_mode ON public.media_draft IS '数据有效性约束：CHECK (((video_input_mode IS NULL) OR (video_input_mode IN (''TEXT'', ''START_END'', ''GENERAL_REFERENCE''))))';
COMMENT ON CONSTRAINT media_draft_pkey ON public.media_draft IS '主键：唯一标识媒体卡片独立草稿、参数、能力、风格和引用提及的记录  (project_id, canvas_item_id)';
COMMENT ON CONSTRAINT fk_media_draft_canvas_item ON public.media_draft IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, canvas_item_id) REFERENCES public.canvas_item(project_id, id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT fk_media_draft_capability ON public.media_draft IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (capability_id) REFERENCES public.media_capability(id)';
COMMENT ON CONSTRAINT fk_media_draft_style ON public.media_draft IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (style_id) REFERENCES public.media_style(id)';
COMMENT ON INDEX public.media_draft_pkey IS '支撑主键 media_draft.media_draft_pkey';

-- 持久执行单元；短事务认领、租约及 fencing epoch 保护所有状态写入。
CREATE TABLE public.task (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    run_id uuid, -- 所属 Agent Run；用户直连任务为空
    step_key character varying(160) NOT NULL, -- 项目与 Run 作用域内的执行步骤去重键
    kind character varying(40) NOT NULL, -- 业务类型，允许值由 CHECK 约束限定
    status character varying(40) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    input_json jsonb NOT NULL, -- 受理时固定的命令输入
    input_hash character(64) NOT NULL, -- 规范化任务固定输入 SHA-256 摘要
    output_json jsonb, -- 任务已提交结果或公开回答流进度
    provider_request_id character varying(240), -- 外部已受理请求标识，供状态查询与结果归档
    attempt_no integer NOT NULL, -- 用户批准的独立执行尝试序号
    next_action_at timestamp with time zone NOT NULL, -- 调度器下一次允许认领时间
    lease_owner character varying(160), -- 当前认领 Worker 标识
    lease_until timestamp with time zone, -- 当前认领租约过期时间
    lease_epoch bigint DEFAULT 0 NOT NULL, -- Monotonic fencing token incremented on every claim or reclaim.
    version bigint DEFAULT 0 NOT NULL, -- 乐观并发控制版本，更新时递增并校验预期值
    error_code character varying(120), -- 稳定错误代码，不含堆栈或凭据
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    completed_at timestamp with time zone, -- 终态完成时间；未完成时为空
    cancel_requested boolean DEFAULT false NOT NULL, -- 用户要求停止本系统后续编排；不承诺外部停止或退款
    capability_id uuid, -- 固定媒体能力身份
    capability_version integer, -- 固定的不可变媒体能力版本
    connection_id uuid, -- 不可变媒体连接所属身份或输入来源连线身份
    connection_version integer, -- 任务固定使用的不可变媒体连接版本
    provider_result_manifest jsonb, -- Private immutable provider result checkpoint. Never exposed in task DTOs, SSE or project export.
    CONSTRAINT ck_task_attempt_positive CHECK ((attempt_no > 0)),
    CONSTRAINT ck_task_completion CHECK ((((status IN ('SUCCEEDED', 'FAILED', 'CANCELED')) AND (completed_at IS NOT NULL)) OR ((status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED')) AND (completed_at IS NULL)))),
    CONSTRAINT ck_task_input_hash CHECK ((input_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_task_kind CHECK ((kind IN ('AGENT_TURN', 'TEXT_GENERATION', 'IMAGE_GENERATION', 'VIDEO_GENERATION', 'AUDIO_GENERATION'))),
    CONSTRAINT ck_task_lease_epoch_non_negative CHECK ((lease_epoch >= 0)),
    CONSTRAINT ck_task_lease_pair CHECK ((((lease_owner IS NULL) AND (lease_until IS NULL)) OR ((lease_owner IS NOT NULL) AND (lease_until IS NOT NULL)))),
    CONSTRAINT ck_task_media_binding CHECK ((((capability_id IS NULL) AND (capability_version IS NULL) AND (connection_id IS NULL) AND (connection_version IS NULL)) OR ((capability_id IS NOT NULL) AND (capability_version IS NOT NULL) AND (connection_id IS NOT NULL) AND (connection_version IS NOT NULL)))),
    CONSTRAINT ck_task_provider_result_manifest CHECK (((provider_result_manifest IS NULL) OR COALESCE(((jsonb_typeof(provider_result_manifest) = 'object'::text) AND ((provider_result_manifest ->> 'schemaVersion'::text) = '1'::text) AND (jsonb_typeof((provider_result_manifest -> 'results'::text)) = 'array'::text) AND ((jsonb_array_length((provider_result_manifest -> 'results'::text)) >= 1) AND (jsonb_array_length((provider_result_manifest -> 'results'::text)) <= 16))), false))),
    CONSTRAINT ck_task_run_scope CHECK (((run_id IS NOT NULL) OR (kind IN ('TEXT_GENERATION', 'IMAGE_GENERATION', 'VIDEO_GENERATION', 'AUDIO_GENERATION')))),
    CONSTRAINT ck_task_status CHECK ((status IN ('READY', 'RUNNING', 'SUBMITTING', 'WAITING_PROVIDER', 'UNKNOWN', 'BLOCKED', 'SUCCEEDED', 'FAILED', 'CANCELED'))),
    CONSTRAINT ck_task_step_key_not_blank CHECK ((length(btrim((step_key)::text)) > 0)),
    CONSTRAINT ck_task_version_non_negative CHECK ((version >= 0)),
    CONSTRAINT task_pkey PRIMARY KEY (id),
    CONSTRAINT uq_task_project_id UNIQUE (project_id, id),
    CONSTRAINT fk_task_media_capability FOREIGN KEY (capability_id, capability_version) REFERENCES public.media_capability_version(capability_id, version),
    CONSTRAINT fk_task_media_connection FOREIGN KEY (connection_id, connection_version) REFERENCES public.media_provider_connection_version(connection_id, version),
    CONSTRAINT fk_task_media_owner FOREIGN KEY (connection_id, capability_id) REFERENCES public.media_capability(connection_id, id),
    CONSTRAINT fk_task_project FOREIGN KEY (project_id) REFERENCES public.project(id),
    CONSTRAINT fk_task_run FOREIGN KEY (project_id, run_id) REFERENCES public.agent_run(project_id, id)
);

COMMENT ON TABLE public.task IS '持久执行单元；短事务认领、租约及 fencing epoch 保护所有状态写入';
COMMENT ON COLUMN public.task.id IS '记录身份';
COMMENT ON COLUMN public.task.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.task.run_id IS '所属 Agent Run；用户直连任务为空';
COMMENT ON COLUMN public.task.step_key IS '项目与 Run 作用域内的执行步骤去重键';
COMMENT ON COLUMN public.task.kind IS '业务类型，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.task.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.task.input_json IS '受理时固定的命令输入';
COMMENT ON COLUMN public.task.input_hash IS '规范化任务固定输入 SHA-256 摘要';
COMMENT ON COLUMN public.task.output_json IS '任务已提交结果或公开回答流进度';
COMMENT ON COLUMN public.task.provider_request_id IS '外部已受理请求标识，供状态查询与结果归档';
COMMENT ON COLUMN public.task.attempt_no IS '用户批准的独立执行尝试序号';
COMMENT ON COLUMN public.task.next_action_at IS '调度器下一次允许认领时间';
COMMENT ON COLUMN public.task.lease_owner IS '当前认领 Worker 标识';
COMMENT ON COLUMN public.task.lease_until IS '当前认领租约过期时间';
COMMENT ON COLUMN public.task.lease_epoch IS 'Monotonic fencing token incremented on every claim or reclaim.';
COMMENT ON COLUMN public.task.version IS '乐观并发控制版本，更新时递增并校验预期值';
COMMENT ON COLUMN public.task.error_code IS '稳定错误代码，不含堆栈或凭据';
COMMENT ON COLUMN public.task.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.task.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON COLUMN public.task.completed_at IS '终态完成时间；未完成时为空';
COMMENT ON COLUMN public.task.cancel_requested IS '用户要求停止本系统后续编排；不承诺外部停止或退款';
COMMENT ON COLUMN public.task.capability_id IS '固定媒体能力身份';
COMMENT ON COLUMN public.task.capability_version IS '固定的不可变媒体能力版本';
COMMENT ON COLUMN public.task.connection_id IS '不可变媒体连接所属身份或输入来源连线身份';
COMMENT ON COLUMN public.task.connection_version IS '任务固定使用的不可变媒体连接版本';
COMMENT ON COLUMN public.task.provider_result_manifest IS 'Private immutable provider result checkpoint. Never exposed in task DTOs, SSE or project export.';
COMMENT ON CONSTRAINT ck_task_attempt_positive ON public.task IS '数据有效性约束：CHECK ((attempt_no > 0))';
COMMENT ON CONSTRAINT ck_task_completion ON public.task IS '数据有效性约束：CHECK ((((status IN (''SUCCEEDED'', ''FAILED'', ''CANCELED'')) AND (completed_at IS NOT NULL)) OR ((status NOT IN (''SUCCEEDED'', ''FAILED'', ''CANCELED'')) AND (completed_at IS NULL))))';
COMMENT ON CONSTRAINT ck_task_input_hash ON public.task IS '数据有效性约束：CHECK ((input_hash ~ ''^[0-9a-f]{64}$''::text))';
COMMENT ON CONSTRAINT ck_task_kind ON public.task IS '数据有效性约束：CHECK ((kind IN (''AGENT_TURN'', ''TEXT_GENERATION'', ''IMAGE_GENERATION'', ''VIDEO_GENERATION'', ''AUDIO_GENERATION'')))';
COMMENT ON CONSTRAINT ck_task_lease_epoch_non_negative ON public.task IS '数据有效性约束：CHECK ((lease_epoch >= 0))';
COMMENT ON CONSTRAINT ck_task_lease_pair ON public.task IS '数据有效性约束：CHECK ((((lease_owner IS NULL) AND (lease_until IS NULL)) OR ((lease_owner IS NOT NULL) AND (lease_until IS NOT NULL))))';
COMMENT ON CONSTRAINT ck_task_media_binding ON public.task IS '数据有效性约束：CHECK ((((capability_id IS NULL) AND (capability_version IS NULL) AND (connection_id IS NULL) AND (connection_version IS NULL)) OR ((capability_id IS NOT NULL) AND (capability_version IS NOT NULL) AND (connection_id IS NOT NULL) AND (connection_version IS NOT NULL))))';
COMMENT ON CONSTRAINT ck_task_provider_result_manifest ON public.task IS '数据有效性约束：CHECK (((provider_result_manifest IS NULL) OR COALESCE(((jsonb_typeof(provider_result_manifest) = ''object''::text) AND ((provider_result_manifest ->> ''schemaVersion''::text) = ''1''::text) AND (jsonb_typeof((provider_result_manifest -> ''results''::text)) = ''array''::text) AND ((jsonb_array_length((provider_result_manifest -> ''results''::text)) >= 1) AND (jsonb_array_length((provider_result_manifest -> ''results''::text)) <= 16))), false)))';
COMMENT ON CONSTRAINT ck_task_run_scope ON public.task IS '用户直连任务没有 Run，且只允许文字、图片、视频或音频生成类型；Agent 回合必须隶属 Run';
COMMENT ON CONSTRAINT ck_task_status ON public.task IS '数据有效性约束：CHECK ((status IN (''READY'', ''RUNNING'', ''SUBMITTING'', ''WAITING_PROVIDER'', ''UNKNOWN'', ''BLOCKED'', ''SUCCEEDED'', ''FAILED'', ''CANCELED'')))';
COMMENT ON CONSTRAINT ck_task_step_key_not_blank ON public.task IS '数据有效性约束：CHECK ((length(btrim((step_key)::text)) > 0))';
COMMENT ON CONSTRAINT ck_task_version_non_negative ON public.task IS '数据有效性约束：CHECK ((version >= 0))';
COMMENT ON CONSTRAINT task_pkey ON public.task IS '主键：唯一标识持久执行单元的记录  (id)';
COMMENT ON CONSTRAINT uq_task_project_id ON public.task IS '唯一约束：禁止作用域内重复记录  (project_id, id)';
COMMENT ON CONSTRAINT fk_task_media_capability ON public.task IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (capability_id, capability_version) REFERENCES public.media_capability_version(capability_id, version)';
COMMENT ON CONSTRAINT fk_task_media_connection ON public.task IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (connection_id, connection_version) REFERENCES public.media_provider_connection_version(connection_id, version)';
COMMENT ON CONSTRAINT fk_task_media_owner ON public.task IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (connection_id, capability_id) REFERENCES public.media_capability(connection_id, id)';
COMMENT ON CONSTRAINT fk_task_project ON public.task IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id) REFERENCES public.project(id)';
COMMENT ON CONSTRAINT fk_task_run ON public.task IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, run_id) REFERENCES public.agent_run(project_id, id)';
COMMENT ON INDEX public.task_pkey IS '支撑主键 task.task_pkey';
COMMENT ON INDEX public.uq_task_project_id IS '支撑唯一约束 task.uq_task_project_id';

CREATE INDEX ix_task_claim_ready ON public.task USING btree (next_action_at, created_at, id) WHERE ((status)::text = 'READY'::text);
COMMENT ON INDEX public.ix_task_claim_ready IS '查询索引：支持持久执行单元的定位与排序；USING btree (next_action_at, created_at, id) WHERE ((status)::text = ''READY''::text)';

CREATE INDEX ix_task_direct_project ON public.task USING btree (project_id, status, created_at) WHERE (run_id IS NULL);
COMMENT ON INDEX public.ix_task_direct_project IS '查询索引：支持持久执行单元的定位与排序；USING btree (project_id, status, created_at) WHERE (run_id IS NULL)';

CREATE INDEX ix_task_observable_states ON public.task USING btree (status) WHERE (status IN ('READY', 'UNKNOWN', 'BLOCKED'));
COMMENT ON INDEX public.ix_task_observable_states IS '查询索引：支持持久执行单元的定位与排序；USING btree (status) WHERE (status IN (''READY'', ''UNKNOWN'', ''BLOCKED''))';

CREATE INDEX ix_task_reclaim_running ON public.task USING btree (lease_until, created_at, id) WHERE ((status)::text = 'RUNNING'::text);
COMMENT ON INDEX public.ix_task_reclaim_running IS '查询索引：支持持久执行单元的定位与排序；USING btree (lease_until, created_at, id) WHERE ((status)::text = ''RUNNING''::text)';

CREATE INDEX ix_task_run ON public.task USING btree (project_id, run_id, created_at, id);
COMMENT ON INDEX public.ix_task_run IS '查询索引：支持持久执行单元的定位与排序；USING btree (project_id, run_id, created_at, id)';

CREATE INDEX ix_task_submitting_lease ON public.task USING btree (lease_until, id) WHERE ((status)::text = 'SUBMITTING'::text);
COMMENT ON INDEX public.ix_task_submitting_lease IS '查询索引：支持持久执行单元的定位与排序；USING btree (lease_until, id) WHERE ((status)::text = ''SUBMITTING''::text)';

CREATE UNIQUE INDEX uq_task_direct_key ON public.task USING btree (project_id, step_key) WHERE (run_id IS NULL);
COMMENT ON INDEX public.uq_task_direct_key IS '唯一索引：保证限定范围内不重复；USING btree (project_id, step_key) WHERE (run_id IS NULL)';

CREATE UNIQUE INDEX uq_task_run_step_attempt ON public.task USING btree (run_id, step_key, attempt_no);
COMMENT ON INDEX public.uq_task_run_step_attempt IS '唯一索引：保证限定范围内不重复；USING btree (run_id, step_key, attempt_no)';

-- Provider 调用公开审计元数据，不包含凭据或模型私有推理。
CREATE TABLE public.call_log (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 原所属项目的历史标识；不设外键，查询仍须校验项目权限
    task_id uuid, -- 原持久任务的历史标识；允许任务清理后保留
    run_id uuid, -- 原 Agent Run 的历史标识；允许执行对象清理后保留，直连任务为空
    step_index integer, -- Run 内模型回合序号
    kind character varying(12) NOT NULL, -- 业务类型，允许值由 CHECK 约束限定
    operation character varying(12) NOT NULL, -- 本次调用执行的操作名称
    status character varying(12) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    provider character varying(160), -- Provider 或存储实现类型
    model character varying(160), -- 本次调用的模型标识
    trace_id character(32) NOT NULL, -- 调用链关联标识
    provider_request_id character varying(240), -- 外部已受理请求标识，供状态查询与结果归档
    error_code character varying(120), -- 稳定错误代码，不含堆栈或凭据
    started_at timestamp with time zone NOT NULL, -- 调用开始时间
    responded_at timestamp with time zone, -- 完整响应持久化时间
    duration_ms bigint, -- 媒体或调用持续时间，单位毫秒
    mock boolean NOT NULL, -- 明确标记演示调用，不证明真实 Provider 已接通
    CONSTRAINT call_log_check1 CHECK (((((status)::text = 'RUNNING'::text) AND (responded_at IS NULL) AND (duration_ms IS NULL)) OR (((status)::text <> 'RUNNING'::text) AND (responded_at IS NOT NULL) AND (duration_ms IS NOT NULL)))),
    CONSTRAINT call_log_duration_ms_check CHECK ((duration_ms >= 0)),
    CONSTRAINT call_log_kind_check CHECK ((kind IN ('LLM', 'IMAGE', 'VIDEO', 'AUDIO'))),
    CONSTRAINT call_log_operation_check CHECK ((operation IN ('CHAT', 'SUBMIT', 'POLL'))),
    CONSTRAINT call_log_status_check CHECK ((status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))),
    CONSTRAINT call_log_trace_id_check CHECK ((trace_id ~ '^[0-9a-f]{32}$'::text)),
    CONSTRAINT ck_call_log_operation_scope CHECK (((((operation)::text = 'CHAT'::text) AND ((kind)::text = 'LLM'::text) AND (((run_id IS NOT NULL) AND (step_index IS NOT NULL) AND (step_index >= 0)) OR ((task_id IS NOT NULL) AND (run_id IS NULL) AND (step_index IS NULL)))) OR ((operation IN ('SUBMIT', 'POLL')) AND (kind IN ('IMAGE', 'VIDEO', 'AUDIO')) AND (task_id IS NOT NULL) AND (step_index IS NULL)))),
    CONSTRAINT call_log_pkey PRIMARY KEY (id),
    CONSTRAINT call_log_trace_id_key UNIQUE (trace_id)
);

COMMENT ON TABLE public.call_log IS 'Provider 调用公开审计元数据，不包含凭据或模型私有推理';
COMMENT ON COLUMN public.call_log.id IS '记录身份';
COMMENT ON COLUMN public.call_log.project_id IS '原所属项目的历史标识；不设外键，查询仍须校验项目权限';
COMMENT ON COLUMN public.call_log.task_id IS '原持久任务的历史标识；允许任务清理后保留';
COMMENT ON COLUMN public.call_log.run_id IS '原 Agent Run 的历史标识；允许执行对象清理后保留，直连任务为空';
COMMENT ON COLUMN public.call_log.step_index IS 'Run 内模型回合序号';
COMMENT ON COLUMN public.call_log.kind IS '业务类型，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.call_log.operation IS '本次调用执行的操作名称';
COMMENT ON COLUMN public.call_log.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.call_log.provider IS 'Provider 或存储实现类型';
COMMENT ON COLUMN public.call_log.model IS '本次调用的模型标识';
COMMENT ON COLUMN public.call_log.trace_id IS '调用链关联标识';
COMMENT ON COLUMN public.call_log.provider_request_id IS '外部已受理请求标识，供状态查询与结果归档';
COMMENT ON COLUMN public.call_log.error_code IS '稳定错误代码，不含堆栈或凭据';
COMMENT ON COLUMN public.call_log.started_at IS '调用开始时间';
COMMENT ON COLUMN public.call_log.responded_at IS '完整响应持久化时间';
COMMENT ON COLUMN public.call_log.duration_ms IS '媒体或调用持续时间，单位毫秒';
COMMENT ON COLUMN public.call_log.mock IS '明确标记演示调用，不证明真实 Provider 已接通';
COMMENT ON CONSTRAINT call_log_check1 ON public.call_log IS '数据有效性约束：CHECK (((((status)::text = ''RUNNING''::text) AND (responded_at IS NULL) AND (duration_ms IS NULL)) OR (((status)::text <> ''RUNNING''::text) AND (responded_at IS NOT NULL) AND (duration_ms IS NOT NULL))))';
COMMENT ON CONSTRAINT call_log_duration_ms_check ON public.call_log IS '数据有效性约束：CHECK ((duration_ms >= 0))';
COMMENT ON CONSTRAINT call_log_kind_check ON public.call_log IS '数据有效性约束：CHECK ((kind IN (''LLM'', ''IMAGE'', ''VIDEO'', ''AUDIO'')))';
COMMENT ON CONSTRAINT call_log_operation_check ON public.call_log IS '数据有效性约束：CHECK ((operation IN (''CHAT'', ''SUBMIT'', ''POLL'')))';
COMMENT ON CONSTRAINT call_log_status_check ON public.call_log IS '数据有效性约束：CHECK ((status IN (''RUNNING'', ''SUCCEEDED'', ''FAILED'', ''UNKNOWN'')))';
COMMENT ON CONSTRAINT call_log_trace_id_check ON public.call_log IS '数据有效性约束：CHECK ((trace_id ~ ''^[0-9a-f]{32}$''::text))';
COMMENT ON CONSTRAINT ck_call_log_operation_scope ON public.call_log IS '数据有效性约束：CHECK (((((operation)::text = ''CHAT''::text) AND ((kind)::text = ''LLM''::text) AND (((run_id IS NOT NULL) AND (step_index IS NOT NULL) AND (step_index >= 0)) OR ((task_id IS NOT NULL) AND (run_id IS NULL) AND (step_index IS NULL)))) OR ((operation IN (''SUBMIT'', ''POLL'')) AND (kind IN (''IMAGE'', ''VIDEO'', ''AUDIO'')) AND (task_id IS NOT NULL) AND (step_index IS NULL))))';
COMMENT ON CONSTRAINT call_log_pkey ON public.call_log IS '主键：唯一标识Provider 调用公开审计元数据，不包含凭据或模型私有推理的记录  (id)';
COMMENT ON CONSTRAINT call_log_trace_id_key ON public.call_log IS '唯一约束：禁止作用域内重复记录  (trace_id)';
COMMENT ON INDEX public.call_log_pkey IS '支撑主键 call_log.call_log_pkey';
COMMENT ON INDEX public.call_log_trace_id_key IS '支撑唯一约束 call_log.call_log_trace_id_key';

CREATE INDEX ix_call_log_project_time ON public.call_log USING btree (project_id, started_at DESC, id DESC);
COMMENT ON INDEX public.ix_call_log_project_time IS '查询索引：支持Provider 调用公开审计元数据，不包含凭据或模型私有推理的定位与排序；USING btree (project_id, started_at DESC, id DESC)';

CREATE INDEX ix_call_log_round ON public.call_log USING btree (run_id, step_index) WHERE ((kind)::text = 'LLM'::text);
COMMENT ON INDEX public.ix_call_log_round IS '查询索引：支持Provider 调用公开审计元数据，不包含凭据或模型私有推理的定位与排序；USING btree (run_id, step_index) WHERE ((kind)::text = ''LLM''::text)';

CREATE INDEX ix_call_log_task ON public.call_log USING btree (task_id, operation);
COMMENT ON INDEX public.ix_call_log_task IS '查询索引：支持Provider 调用公开审计元数据，不包含凭据或模型私有推理的定位与排序；USING btree (task_id, operation)';

-- 媒体卡片显式选取的精确输入版本、角色、顺序和显示颜色。
CREATE TABLE public.canvas_item_media_input (
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    canvas_item_id uuid NOT NULL, -- 固定的目标或上下文画布卡片
    artifact_version_id uuid NOT NULL, -- 固定的不可变产物版本
    input_role character varying(24) NOT NULL, -- 媒体输入角色
    input_order integer NOT NULL, -- 同角色媒体输入顺序
    color character varying(7) NOT NULL, -- 媒体输入来源连线的显示颜色
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT ck_canvas_item_media_input_color CHECK (((color)::text ~ '^#[0-9A-F]{6}$'::text)),
    CONSTRAINT ck_canvas_item_media_input_order CHECK ((input_order >= 0)),
    CONSTRAINT ck_canvas_item_media_input_role CHECK ((input_role IN ('REFERENCE', 'START_FRAME', 'END_FRAME', 'AUDIO_REFERENCE', 'VIDEO_REFERENCE'))),
    CONSTRAINT canvas_item_media_input_pkey PRIMARY KEY (project_id, canvas_item_id, artifact_version_id),
    CONSTRAINT uq_canvas_item_media_input_order UNIQUE (project_id, canvas_item_id, input_order),
    CONSTRAINT fk_canvas_item_media_input_draft FOREIGN KEY (project_id, canvas_item_id) REFERENCES public.media_draft(project_id, canvas_item_id) ON DELETE CASCADE,
    CONSTRAINT fk_canvas_item_media_input_version FOREIGN KEY (project_id, artifact_version_id) REFERENCES public.artifact_version(project_id, id)
);

COMMENT ON TABLE public.canvas_item_media_input IS '媒体卡片显式选取的精确输入版本、角色、顺序和显示颜色';
COMMENT ON COLUMN public.canvas_item_media_input.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.canvas_item_media_input.canvas_item_id IS '固定的目标或上下文画布卡片';
COMMENT ON COLUMN public.canvas_item_media_input.artifact_version_id IS '固定的不可变产物版本';
COMMENT ON COLUMN public.canvas_item_media_input.input_role IS '媒体输入角色';
COMMENT ON COLUMN public.canvas_item_media_input.input_order IS '同角色媒体输入顺序';
COMMENT ON COLUMN public.canvas_item_media_input.color IS '媒体输入来源连线的显示颜色';
COMMENT ON COLUMN public.canvas_item_media_input.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.canvas_item_media_input.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT ck_canvas_item_media_input_color ON public.canvas_item_media_input IS '数据有效性约束：CHECK (((color)::text ~ ''^#[0-9A-F]{6}$''::text))';
COMMENT ON CONSTRAINT ck_canvas_item_media_input_order ON public.canvas_item_media_input IS '数据有效性约束：CHECK ((input_order >= 0))';
COMMENT ON CONSTRAINT ck_canvas_item_media_input_role ON public.canvas_item_media_input IS '数据有效性约束：CHECK ((input_role IN (''REFERENCE'', ''START_FRAME'', ''END_FRAME'', ''AUDIO_REFERENCE'', ''VIDEO_REFERENCE'')))';
COMMENT ON CONSTRAINT canvas_item_media_input_pkey ON public.canvas_item_media_input IS '主键：唯一标识媒体卡片显式选取的精确输入版本、角色、顺序和显示颜色的记录  (project_id, canvas_item_id, artifact_version_id)';
COMMENT ON CONSTRAINT uq_canvas_item_media_input_order ON public.canvas_item_media_input IS '唯一约束：禁止作用域内重复记录  (project_id, canvas_item_id, input_order)';
COMMENT ON CONSTRAINT fk_canvas_item_media_input_draft ON public.canvas_item_media_input IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, canvas_item_id) REFERENCES public.media_draft(project_id, canvas_item_id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT fk_canvas_item_media_input_version ON public.canvas_item_media_input IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, artifact_version_id) REFERENCES public.artifact_version(project_id, id)';
COMMENT ON INDEX public.canvas_item_media_input_pkey IS '支撑主键 canvas_item_media_input.canvas_item_media_input_pkey';
COMMENT ON INDEX public.uq_canvas_item_media_input_order IS '支撑唯一约束 canvas_item_media_input.uq_canvas_item_media_input_order';

CREATE UNIQUE INDEX uq_canvas_item_media_input_end_frame ON public.canvas_item_media_input USING btree (project_id, canvas_item_id, input_role) WHERE ((input_role)::text = 'END_FRAME'::text);
COMMENT ON INDEX public.uq_canvas_item_media_input_end_frame IS '唯一索引：保证限定范围内不重复；USING btree (project_id, canvas_item_id, input_role) WHERE ((input_role)::text = ''END_FRAME''::text)';

CREATE UNIQUE INDEX uq_canvas_item_media_input_start_frame ON public.canvas_item_media_input USING btree (project_id, canvas_item_id, input_role) WHERE ((input_role)::text = 'START_FRAME'::text);
COMMENT ON INDEX public.uq_canvas_item_media_input_start_frame IS '唯一索引：保证限定范围内不重复；USING btree (project_id, canvas_item_id, input_role) WHERE ((input_role)::text = ''START_FRAME''::text)';

-- 固定任务租约和连接能力版本的外部提交尝试；结果未知时禁止自动重提。
CREATE TABLE public.provider_attempt (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    task_id uuid NOT NULL, -- 持久任务身份
    lease_epoch bigint NOT NULL, -- 任务租约 fencing epoch，旧 Worker 不得回写结果
    status character varying(24) NOT NULL, -- REJECTED is an explicit provider rejection; uncertain transport failures remain UNKNOWN.
    request_key uuid NOT NULL, -- 在外部调用前已提交的稳定请求去重键
    provider_request_id character varying(240), -- 外部已受理请求标识，供状态查询与结果归档
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    capability_id uuid, -- 固定媒体能力身份
    capability_version integer, -- 固定的不可变媒体能力版本
    connection_id uuid, -- 不可变媒体连接所属身份或输入来源连线身份
    connection_version integer, -- 任务固定使用的不可变媒体连接版本
    CONSTRAINT ck_provider_attempt_media_binding CHECK ((((capability_id IS NULL) AND (capability_version IS NULL) AND (connection_id IS NULL) AND (connection_version IS NULL)) OR ((capability_id IS NOT NULL) AND (capability_version IS NOT NULL) AND (connection_id IS NOT NULL) AND (connection_version IS NOT NULL)))),
    CONSTRAINT ck_provider_attempt_status CHECK ((status IN ('SUBMITTING', 'ACCEPTED', 'UNKNOWN', 'REJECTED'))),
    CONSTRAINT provider_attempt_pkey PRIMARY KEY (id),
    CONSTRAINT uq_provider_attempt_epoch UNIQUE (task_id, lease_epoch),
    CONSTRAINT uq_provider_attempt_request_key UNIQUE (request_key),
    CONSTRAINT fk_provider_attempt_media_capability FOREIGN KEY (capability_id, capability_version) REFERENCES public.media_capability_version(capability_id, version),
    CONSTRAINT fk_provider_attempt_media_connection FOREIGN KEY (connection_id, connection_version) REFERENCES public.media_provider_connection_version(connection_id, version),
    CONSTRAINT fk_provider_attempt_media_owner FOREIGN KEY (connection_id, capability_id) REFERENCES public.media_capability(connection_id, id),
    CONSTRAINT fk_provider_attempt_task FOREIGN KEY (project_id, task_id) REFERENCES public.task(project_id, id)
);

COMMENT ON TABLE public.provider_attempt IS '固定任务租约和连接能力版本的外部提交尝试；结果未知时禁止自动重提';
COMMENT ON COLUMN public.provider_attempt.id IS '记录身份';
COMMENT ON COLUMN public.provider_attempt.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.provider_attempt.task_id IS '持久任务身份';
COMMENT ON COLUMN public.provider_attempt.lease_epoch IS '任务租约 fencing epoch，旧 Worker 不得回写结果';
COMMENT ON COLUMN public.provider_attempt.status IS 'REJECTED is an explicit provider rejection; uncertain transport failures remain UNKNOWN.';
COMMENT ON COLUMN public.provider_attempt.request_key IS '在外部调用前已提交的稳定请求去重键';
COMMENT ON COLUMN public.provider_attempt.provider_request_id IS '外部已受理请求标识，供状态查询与结果归档';
COMMENT ON COLUMN public.provider_attempt.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.provider_attempt.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON COLUMN public.provider_attempt.capability_id IS '固定媒体能力身份';
COMMENT ON COLUMN public.provider_attempt.capability_version IS '固定的不可变媒体能力版本';
COMMENT ON COLUMN public.provider_attempt.connection_id IS '不可变媒体连接所属身份或输入来源连线身份';
COMMENT ON COLUMN public.provider_attempt.connection_version IS '任务固定使用的不可变媒体连接版本';
COMMENT ON CONSTRAINT ck_provider_attempt_media_binding ON public.provider_attempt IS '数据有效性约束：CHECK ((((capability_id IS NULL) AND (capability_version IS NULL) AND (connection_id IS NULL) AND (connection_version IS NULL)) OR ((capability_id IS NOT NULL) AND (capability_version IS NOT NULL) AND (connection_id IS NOT NULL) AND (connection_version IS NOT NULL))))';
COMMENT ON CONSTRAINT ck_provider_attempt_status ON public.provider_attempt IS '数据有效性约束：CHECK ((status IN (''SUBMITTING'', ''ACCEPTED'', ''UNKNOWN'', ''REJECTED'')))';
COMMENT ON CONSTRAINT provider_attempt_pkey ON public.provider_attempt IS '主键：唯一标识固定任务租约和连接能力版本的外部提交尝试的记录  (id)';
COMMENT ON CONSTRAINT uq_provider_attempt_epoch ON public.provider_attempt IS '唯一约束：禁止作用域内重复记录  (task_id, lease_epoch)';
COMMENT ON CONSTRAINT uq_provider_attempt_request_key ON public.provider_attempt IS '唯一约束：禁止作用域内重复记录  (request_key)';
COMMENT ON CONSTRAINT fk_provider_attempt_media_capability ON public.provider_attempt IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (capability_id, capability_version) REFERENCES public.media_capability_version(capability_id, version)';
COMMENT ON CONSTRAINT fk_provider_attempt_media_connection ON public.provider_attempt IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (connection_id, connection_version) REFERENCES public.media_provider_connection_version(connection_id, version)';
COMMENT ON CONSTRAINT fk_provider_attempt_media_owner ON public.provider_attempt IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (connection_id, capability_id) REFERENCES public.media_capability(connection_id, id)';
COMMENT ON CONSTRAINT fk_provider_attempt_task ON public.provider_attempt IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, task_id) REFERENCES public.task(project_id, id)';
COMMENT ON INDEX public.provider_attempt_pkey IS '支撑主键 provider_attempt.provider_attempt_pkey';
COMMENT ON INDEX public.uq_provider_attempt_epoch IS '支撑唯一约束 provider_attempt.uq_provider_attempt_epoch';
COMMENT ON INDEX public.uq_provider_attempt_request_key IS '支撑唯一约束 provider_attempt.uq_provider_attempt_request_key';

CREATE INDEX ix_provider_attempt_task ON public.provider_attempt USING btree (task_id, created_at);
COMMENT ON INDEX public.ix_provider_attempt_task IS '查询索引：支持固定任务租约和连接能力版本的外部提交尝试的定位与排序；USING btree (task_id, created_at)';

-- 任务固定目标与受理时的版本选择；结果不能覆盖并发用户选择。
CREATE TABLE public.task_artifact_target (
    task_id uuid NOT NULL, -- 持久任务身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    artifact_id uuid NOT NULL, -- 业务产物身份
    expected_current_version_id uuid, -- 受理时的产物当前版本，结果提交时进行 CAS 校验
    expected_artifact_version bigint NOT NULL, -- 受理时的产物并发控制版本
    canvas_item_id uuid, -- 固定的目标或上下文画布卡片
    CONSTRAINT ck_task_artifact_expected_version CHECK ((expected_artifact_version >= 0)),
    CONSTRAINT task_artifact_target_pkey PRIMARY KEY (task_id),
    CONSTRAINT fk_task_artifact_target_artifact FOREIGN KEY (project_id, artifact_id) REFERENCES public.artifact(project_id, id),
    CONSTRAINT fk_task_artifact_target_canvas_item FOREIGN KEY (project_id, canvas_item_id) REFERENCES public.canvas_item(project_id, id) ON DELETE SET NULL (canvas_item_id),
    CONSTRAINT fk_task_artifact_target_task FOREIGN KEY (project_id, task_id) REFERENCES public.task(project_id, id),
    CONSTRAINT fk_task_artifact_target_version FOREIGN KEY (artifact_id, expected_current_version_id) REFERENCES public.artifact_version(artifact_id, id)
);

COMMENT ON TABLE public.task_artifact_target IS '任务固定目标与受理时的版本选择；结果不能覆盖并发用户选择';
COMMENT ON COLUMN public.task_artifact_target.task_id IS '持久任务身份';
COMMENT ON COLUMN public.task_artifact_target.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.task_artifact_target.artifact_id IS '业务产物身份';
COMMENT ON COLUMN public.task_artifact_target.expected_current_version_id IS '受理时的产物当前版本，结果提交时进行 CAS 校验';
COMMENT ON COLUMN public.task_artifact_target.expected_artifact_version IS '受理时的产物并发控制版本';
COMMENT ON COLUMN public.task_artifact_target.canvas_item_id IS '固定的目标或上下文画布卡片';
COMMENT ON CONSTRAINT ck_task_artifact_expected_version ON public.task_artifact_target IS '数据有效性约束：CHECK ((expected_artifact_version >= 0))';
COMMENT ON CONSTRAINT task_artifact_target_pkey ON public.task_artifact_target IS '主键：唯一标识任务固定目标与受理时的版本选择的记录  (task_id)';
COMMENT ON CONSTRAINT fk_task_artifact_target_artifact ON public.task_artifact_target IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, artifact_id) REFERENCES public.artifact(project_id, id)';
COMMENT ON CONSTRAINT fk_task_artifact_target_canvas_item ON public.task_artifact_target IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, canvas_item_id) REFERENCES public.canvas_item(project_id, id) ON DELETE SET NULL (canvas_item_id)';
COMMENT ON CONSTRAINT fk_task_artifact_target_task ON public.task_artifact_target IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, task_id) REFERENCES public.task(project_id, id)';
COMMENT ON CONSTRAINT fk_task_artifact_target_version ON public.task_artifact_target IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (artifact_id, expected_current_version_id) REFERENCES public.artifact_version(artifact_id, id)';
COMMENT ON INDEX public.task_artifact_target_pkey IS '支撑主键 task_artifact_target.task_artifact_target_pkey';

CREATE INDEX ix_task_artifact_target_canvas_item ON public.task_artifact_target USING btree (project_id, canvas_item_id) WHERE (canvas_item_id IS NOT NULL);
COMMENT ON INDEX public.ix_task_artifact_target_canvas_item IS '查询索引：支持任务固定目标与受理时的版本选择的定位与排序；USING btree (project_id, canvas_item_id) WHERE (canvas_item_id IS NOT NULL)';

-- 失效租约或取消后的晚到结果审计，不自动选用或启动后续任务。
CREATE TABLE public.task_late_result (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    task_id uuid NOT NULL, -- 持久任务身份
    lease_epoch bigint NOT NULL, -- 任务租约 fencing epoch，旧 Worker 不得回写结果
    output_json jsonb NOT NULL, -- 任务已提交结果或公开回答流进度
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT task_late_result_pkey PRIMARY KEY (id),
    CONSTRAINT uq_task_late_result_epoch UNIQUE (task_id, lease_epoch),
    CONSTRAINT fk_task_late_result_task FOREIGN KEY (project_id, task_id) REFERENCES public.task(project_id, id)
);

COMMENT ON TABLE public.task_late_result IS '失效租约或取消后的晚到结果审计，不自动选用或启动后续任务';
COMMENT ON COLUMN public.task_late_result.id IS '记录身份';
COMMENT ON COLUMN public.task_late_result.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.task_late_result.task_id IS '持久任务身份';
COMMENT ON COLUMN public.task_late_result.lease_epoch IS '任务租约 fencing epoch，旧 Worker 不得回写结果';
COMMENT ON COLUMN public.task_late_result.output_json IS '任务已提交结果或公开回答流进度';
COMMENT ON COLUMN public.task_late_result.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT task_late_result_pkey ON public.task_late_result IS '主键：唯一标识失效租约或取消后的晚到结果审计，不自动选用或启动后续任务的记录  (id)';
COMMENT ON CONSTRAINT uq_task_late_result_epoch ON public.task_late_result IS '唯一约束：禁止作用域内重复记录  (task_id, lease_epoch)';
COMMENT ON CONSTRAINT fk_task_late_result_task ON public.task_late_result IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, task_id) REFERENCES public.task(project_id, id)';
COMMENT ON INDEX public.task_late_result_pkey IS '支撑主键 task_late_result.task_late_result_pkey';
COMMENT ON INDEX public.uq_task_late_result_epoch IS '支撑唯一约束 task_late_result.uq_task_late_result_epoch';

-- 用户显式批准的 UNKNOWN 重试对应关系和确认记录。
CREATE TABLE public.task_manual_replacement (
    original_task_id uuid NOT NULL, -- 结果未知的原任务
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    replacement_task_id uuid NOT NULL, -- 用户显式创建的独立新尝试
    approved_by_user_id uuid NOT NULL, -- 确认重复费用风险的可信用户身份
    original_task_version bigint NOT NULL, -- 用户批准重试时核对的原任务版本
    idempotency_key character varying(120) NOT NULL, -- 作用域内的幂等命令键
    confirmation_code character varying(80) NOT NULL, -- 明确重复成本确认的稳定代码
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT ck_manual_replacement_confirmation CHECK (((confirmation_code)::text = 'ACCEPT_POSSIBLE_DUPLICATE_COST'::text)),
    CONSTRAINT ck_manual_replacement_version CHECK ((original_task_version >= 0)),
    CONSTRAINT task_manual_replacement_pkey PRIMARY KEY (original_task_id),
    CONSTRAINT task_manual_replacement_replacement_task_id_key UNIQUE (replacement_task_id),
    CONSTRAINT uq_manual_replacement_command UNIQUE (project_id, approved_by_user_id, idempotency_key),
    CONSTRAINT fk_manual_replacement_new FOREIGN KEY (project_id, replacement_task_id) REFERENCES public.task(project_id, id),
    CONSTRAINT fk_manual_replacement_original FOREIGN KEY (project_id, original_task_id) REFERENCES public.task(project_id, id),
    CONSTRAINT task_manual_replacement_approved_by_user_id_fkey FOREIGN KEY (approved_by_user_id) REFERENCES public.app_user(id)
);

COMMENT ON TABLE public.task_manual_replacement IS '用户显式批准的 UNKNOWN 重试对应关系和确认记录';
COMMENT ON COLUMN public.task_manual_replacement.original_task_id IS '结果未知的原任务';
COMMENT ON COLUMN public.task_manual_replacement.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.task_manual_replacement.replacement_task_id IS '用户显式创建的独立新尝试';
COMMENT ON COLUMN public.task_manual_replacement.approved_by_user_id IS '确认重复费用风险的可信用户身份';
COMMENT ON COLUMN public.task_manual_replacement.original_task_version IS '用户批准重试时核对的原任务版本';
COMMENT ON COLUMN public.task_manual_replacement.idempotency_key IS '作用域内的幂等命令键';
COMMENT ON COLUMN public.task_manual_replacement.confirmation_code IS '明确重复成本确认的稳定代码';
COMMENT ON COLUMN public.task_manual_replacement.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT ck_manual_replacement_confirmation ON public.task_manual_replacement IS '数据有效性约束：CHECK (((confirmation_code)::text = ''ACCEPT_POSSIBLE_DUPLICATE_COST''::text))';
COMMENT ON CONSTRAINT ck_manual_replacement_version ON public.task_manual_replacement IS '数据有效性约束：CHECK ((original_task_version >= 0))';
COMMENT ON CONSTRAINT task_manual_replacement_pkey ON public.task_manual_replacement IS '主键：唯一标识用户显式批准的 UNKNOWN 重试对应关系和确认记录的记录  (original_task_id)';
COMMENT ON CONSTRAINT task_manual_replacement_replacement_task_id_key ON public.task_manual_replacement IS '唯一约束：禁止作用域内重复记录  (replacement_task_id)';
COMMENT ON CONSTRAINT uq_manual_replacement_command ON public.task_manual_replacement IS '唯一约束：禁止作用域内重复记录  (project_id, approved_by_user_id, idempotency_key)';
COMMENT ON CONSTRAINT fk_manual_replacement_new ON public.task_manual_replacement IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, replacement_task_id) REFERENCES public.task(project_id, id)';
COMMENT ON CONSTRAINT fk_manual_replacement_original ON public.task_manual_replacement IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, original_task_id) REFERENCES public.task(project_id, id)';
COMMENT ON CONSTRAINT task_manual_replacement_approved_by_user_id_fkey ON public.task_manual_replacement IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (approved_by_user_id) REFERENCES public.app_user(id)';
COMMENT ON INDEX public.task_manual_replacement_pkey IS '支撑主键 task_manual_replacement.task_manual_replacement_pkey';
COMMENT ON INDEX public.task_manual_replacement_replacement_task_id_key IS '支撑唯一约束 task_manual_replacement.task_manual_replacement_replacement_task_id_key';
COMMENT ON INDEX public.uq_manual_replacement_command IS '支撑唯一约束 task_manual_replacement.uq_manual_replacement_command';

-- 外部状态查询的重试计数；查询重试不代表重新提交生成。
CREATE TABLE public.task_provider_poll_retry (
    task_id uuid NOT NULL, -- 持久任务身份
    failure_count integer NOT NULL, -- 连续外部查询失败次数
    last_error_code character varying(120) NOT NULL, -- 最近外部查询失败的公开错误代码
    updated_at timestamp with time zone NOT NULL, -- 最后状态或配置更新时间（UTC）
    CONSTRAINT ck_task_provider_poll_retry_count CHECK (((failure_count >= 1) AND (failure_count <= 6))),
    CONSTRAINT task_provider_poll_retry_pkey PRIMARY KEY (task_id),
    CONSTRAINT task_provider_poll_retry_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.task(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.task_provider_poll_retry IS '外部状态查询的重试计数；查询重试不代表重新提交生成';
COMMENT ON COLUMN public.task_provider_poll_retry.task_id IS '持久任务身份';
COMMENT ON COLUMN public.task_provider_poll_retry.failure_count IS '连续外部查询失败次数';
COMMENT ON COLUMN public.task_provider_poll_retry.last_error_code IS '最近外部查询失败的公开错误代码';
COMMENT ON COLUMN public.task_provider_poll_retry.updated_at IS '最后状态或配置更新时间（UTC）';
COMMENT ON CONSTRAINT ck_task_provider_poll_retry_count ON public.task_provider_poll_retry IS '数据有效性约束：CHECK (((failure_count >= 1) AND (failure_count <= 6)))';
COMMENT ON CONSTRAINT task_provider_poll_retry_pkey ON public.task_provider_poll_retry IS '主键：唯一标识外部状态查询的重试计数的记录  (task_id)';
COMMENT ON CONSTRAINT task_provider_poll_retry_task_id_fkey ON public.task_provider_poll_retry IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (task_id) REFERENCES public.task(id) ON DELETE CASCADE';
COMMENT ON INDEX public.task_provider_poll_retry_pkey IS '支撑主键 task_provider_poll_retry.task_provider_poll_retry_pkey';

-- 按 Run、回合及 tool_call_id 去重的工具执行账本。
CREATE TABLE public.tool_execution (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    run_id uuid NOT NULL, -- 所属 Agent Run；用户直连任务为空
    step_index integer NOT NULL, -- Run 内模型回合序号
    tool_call_id character varying(200) NOT NULL, -- 模型完整响应中的工具调用标识
    tool_name character varying(120) NOT NULL, -- 受控工具名称
    argument_hash character(64) NOT NULL, -- 规范化工具参数 SHA-256 摘要
    status character varying(20) NOT NULL, -- 持久状态，允许值由 CHECK 约束限定
    result_json jsonb, -- 已提交的结构化执行或审批结果
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    completed_at timestamp with time zone, -- 终态完成时间；未完成时为空
    CONSTRAINT ck_tool_execution_result CHECK (((((status)::text = 'EXECUTING'::text) AND (result_json IS NULL) AND (completed_at IS NULL)) OR (((status)::text = 'COMPLETED'::text) AND (jsonb_typeof(result_json) = 'object'::text) AND (completed_at IS NOT NULL)))),
    CONSTRAINT ck_tool_execution_status CHECK ((status IN ('EXECUTING', 'COMPLETED'))),
    CONSTRAINT ck_tool_execution_step CHECK ((step_index >= 0)),
    CONSTRAINT tool_execution_pkey PRIMARY KEY (id),
    CONSTRAINT uq_tool_execution_call UNIQUE (run_id, step_index, tool_call_id),
    CONSTRAINT fk_tool_execution_run FOREIGN KEY (project_id, run_id) REFERENCES public.agent_run(project_id, id),
    CONSTRAINT fk_tool_execution_turn FOREIGN KEY (run_id, step_index) REFERENCES public.llm_turn(run_id, step_index)
);

COMMENT ON TABLE public.tool_execution IS '按 Run、回合及 tool_call_id 去重的工具执行账本';
COMMENT ON COLUMN public.tool_execution.id IS '记录身份';
COMMENT ON COLUMN public.tool_execution.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.tool_execution.run_id IS '所属 Agent Run；用户直连任务为空';
COMMENT ON COLUMN public.tool_execution.step_index IS 'Run 内模型回合序号';
COMMENT ON COLUMN public.tool_execution.tool_call_id IS '模型完整响应中的工具调用标识';
COMMENT ON COLUMN public.tool_execution.tool_name IS '受控工具名称';
COMMENT ON COLUMN public.tool_execution.argument_hash IS '规范化工具参数 SHA-256 摘要';
COMMENT ON COLUMN public.tool_execution.status IS '持久状态，允许值由 CHECK 约束限定';
COMMENT ON COLUMN public.tool_execution.result_json IS '已提交的结构化执行或审批结果';
COMMENT ON COLUMN public.tool_execution.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN public.tool_execution.completed_at IS '终态完成时间；未完成时为空';
COMMENT ON CONSTRAINT ck_tool_execution_result ON public.tool_execution IS '数据有效性约束：CHECK (((((status)::text = ''EXECUTING''::text) AND (result_json IS NULL) AND (completed_at IS NULL)) OR (((status)::text = ''COMPLETED''::text) AND (jsonb_typeof(result_json) = ''object''::text) AND (completed_at IS NOT NULL))))';
COMMENT ON CONSTRAINT ck_tool_execution_status ON public.tool_execution IS '数据有效性约束：CHECK ((status IN (''EXECUTING'', ''COMPLETED'')))';
COMMENT ON CONSTRAINT ck_tool_execution_step ON public.tool_execution IS '数据有效性约束：CHECK ((step_index >= 0))';
COMMENT ON CONSTRAINT tool_execution_pkey ON public.tool_execution IS '主键：唯一标识按 Run、回合及 tool_call_id 去重的工具执行账本的记录  (id)';
COMMENT ON CONSTRAINT uq_tool_execution_call ON public.tool_execution IS '唯一约束：禁止作用域内重复记录  (run_id, step_index, tool_call_id)';
COMMENT ON CONSTRAINT fk_tool_execution_run ON public.tool_execution IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, run_id) REFERENCES public.agent_run(project_id, id)';
COMMENT ON CONSTRAINT fk_tool_execution_turn ON public.tool_execution IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (run_id, step_index) REFERENCES public.llm_turn(run_id, step_index)';
COMMENT ON INDEX public.tool_execution_pkey IS '支撑主键 tool_execution.tool_execution_pkey';
COMMENT ON INDEX public.uq_tool_execution_call IS '支撑唯一约束 tool_execution.uq_tool_execution_call';

CREATE INDEX ix_tool_execution_run ON public.tool_execution USING btree (project_id, run_id, status);
COMMENT ON INDEX public.ix_tool_execution_run IS '查询索引：支持按 Run、回合及 tool_call_id 去重的工具执行账本的定位与排序；USING btree (project_id, run_id, status)';

-- 使用量预留、结算与释放账本，记录估算或实际费用来源。
CREATE TABLE public.usage_ledger (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 原所属项目的历史标识；不设外键，查询仍须校验项目权限
    run_id uuid, -- 原 Agent Run 的历史标识；允许执行对象清理后保留，直连任务为空
    task_id uuid, -- 原持久任务的历史标识；允许任务清理后保留
    operation_key character varying(180) NOT NULL, -- 使用量账本的业务操作去重键
    entry_type character varying(24) NOT NULL, -- 使用量预留、结算或释放类型
    quantity_json jsonb NOT NULL, -- 图片、视频、音频与 LLM 使用量明细
    estimated_cost numeric(18,6), -- 受理时估算费用；未知时为空
    actual_cost numeric(18,6), -- 实际确认费用；未知时为空
    currency character(3), -- 费用币种代码
    cost_status character varying(16) NOT NULL, -- 费用已知、估算或未知状态
    cost_source character varying(80) NOT NULL, -- 计费数据来源
    provider_config_version integer, -- 实际 LLM 或媒体连接配置版本审计
    workflow_version character varying(120), -- 固定工作流或适配器映射版本
    model_id character varying(160), -- 实际使用的模型标识
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT ck_usage_amount_nonnegative CHECK ((((estimated_cost IS NULL) OR (estimated_cost >= (0)::numeric)) AND ((actual_cost IS NULL) OR (actual_cost >= (0)::numeric)))),
    CONSTRAINT ck_usage_config_version CHECK (((provider_config_version IS NULL) OR (provider_config_version > 0))),
    CONSTRAINT ck_usage_cost_status CHECK ((cost_status IN ('KNOWN', 'ESTIMATED', 'UNKNOWN'))),
    CONSTRAINT ck_usage_entry_type CHECK ((entry_type IN ('RESERVATION', 'SETTLEMENT', 'RELEASE'))),
    CONSTRAINT ck_usage_unknown_amount CHECK ((((cost_status)::text <> 'UNKNOWN'::text) OR ((estimated_cost IS NULL) AND (actual_cost IS NULL)))),
    CONSTRAINT usage_ledger_pkey PRIMARY KEY (id),
    CONSTRAINT usage_ledger_operation_key_key UNIQUE (operation_key)
);

COMMENT ON TABLE public.usage_ledger IS '使用量预留、结算与释放账本，记录估算或实际费用来源';
COMMENT ON COLUMN public.usage_ledger.id IS '记录身份';
COMMENT ON COLUMN public.usage_ledger.project_id IS '原所属项目的历史标识；不设外键，查询仍须校验项目权限';
COMMENT ON COLUMN public.usage_ledger.run_id IS '原 Agent Run 的历史标识；允许执行对象清理后保留，直连任务为空';
COMMENT ON COLUMN public.usage_ledger.task_id IS '原持久任务的历史标识；允许任务清理后保留';
COMMENT ON COLUMN public.usage_ledger.operation_key IS '使用量账本的业务操作去重键';
COMMENT ON COLUMN public.usage_ledger.entry_type IS '使用量预留、结算或释放类型';
COMMENT ON COLUMN public.usage_ledger.quantity_json IS '图片、视频、音频与 LLM 使用量明细';
COMMENT ON COLUMN public.usage_ledger.estimated_cost IS '受理时估算费用；未知时为空';
COMMENT ON COLUMN public.usage_ledger.actual_cost IS '实际确认费用；未知时为空';
COMMENT ON COLUMN public.usage_ledger.currency IS '费用币种代码';
COMMENT ON COLUMN public.usage_ledger.cost_status IS '费用已知、估算或未知状态';
COMMENT ON COLUMN public.usage_ledger.cost_source IS '计费数据来源';
COMMENT ON COLUMN public.usage_ledger.provider_config_version IS '实际 LLM 或媒体连接配置版本审计';
COMMENT ON COLUMN public.usage_ledger.workflow_version IS '固定工作流或适配器映射版本';
COMMENT ON COLUMN public.usage_ledger.model_id IS '实际使用的模型标识';
COMMENT ON COLUMN public.usage_ledger.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT ck_usage_amount_nonnegative ON public.usage_ledger IS '数据有效性约束：CHECK ((((estimated_cost IS NULL) OR (estimated_cost >= (0)::numeric)) AND ((actual_cost IS NULL) OR (actual_cost >= (0)::numeric))))';
COMMENT ON CONSTRAINT ck_usage_config_version ON public.usage_ledger IS '数据有效性约束：CHECK (((provider_config_version IS NULL) OR (provider_config_version > 0)))';
COMMENT ON CONSTRAINT ck_usage_cost_status ON public.usage_ledger IS '数据有效性约束：CHECK ((cost_status IN (''KNOWN'', ''ESTIMATED'', ''UNKNOWN'')))';
COMMENT ON CONSTRAINT ck_usage_entry_type ON public.usage_ledger IS '数据有效性约束：CHECK ((entry_type IN (''RESERVATION'', ''SETTLEMENT'', ''RELEASE'')))';
COMMENT ON CONSTRAINT ck_usage_unknown_amount ON public.usage_ledger IS '数据有效性约束：CHECK ((((cost_status)::text <> ''UNKNOWN''::text) OR ((estimated_cost IS NULL) AND (actual_cost IS NULL))))';
COMMENT ON CONSTRAINT usage_ledger_operation_key_key ON public.usage_ledger IS '唯一约束：禁止作用域内重复记录  (operation_key)';
COMMENT ON CONSTRAINT usage_ledger_pkey ON public.usage_ledger IS '主键：唯一标识使用量预留、结算与释放账本，记录估算或实际费用来源的记录  (id)';
COMMENT ON INDEX public.usage_ledger_pkey IS '支撑主键 usage_ledger.usage_ledger_pkey';
COMMENT ON INDEX public.usage_ledger_operation_key_key IS '支撑唯一约束 usage_ledger.usage_ledger_operation_key_key';

CREATE INDEX ix_usage_ledger_project_created ON public.usage_ledger USING btree (project_id, created_at DESC, id DESC);
COMMENT ON INDEX public.ix_usage_ledger_project_created IS '查询索引：支持使用量预留、结算与释放账本，记录估算或实际费用来源的定位与排序；USING btree (project_id, created_at DESC, id DESC)';

-- 仅在明确启用调试时保存的已脱敏请求与响应正文。
CREATE TABLE public.call_log_debug (
    call_id uuid NOT NULL, -- 对应的公开调用审计记录
    schema_version integer DEFAULT 1 NOT NULL, -- 持久 JSON 内容格式版本
    exchanges_json jsonb NOT NULL, -- 已脱敏调用交换正文数组
    CONSTRAINT call_log_debug_exchanges_json_check CHECK ((jsonb_typeof(exchanges_json) = 'array'::text)),
    CONSTRAINT call_log_debug_schema_version_check CHECK ((schema_version = 1)),
    CONSTRAINT call_log_debug_pkey PRIMARY KEY (call_id),
    CONSTRAINT call_log_debug_call_id_fkey FOREIGN KEY (call_id) REFERENCES public.call_log(id) ON DELETE CASCADE
);

COMMENT ON TABLE public.call_log_debug IS '仅在明确启用调试时保存的已脱敏请求与响应正文';
COMMENT ON COLUMN public.call_log_debug.call_id IS '对应的公开调用审计记录';
COMMENT ON COLUMN public.call_log_debug.schema_version IS '持久 JSON 内容格式版本';
COMMENT ON COLUMN public.call_log_debug.exchanges_json IS '已脱敏调用交换正文数组';
COMMENT ON CONSTRAINT call_log_debug_exchanges_json_check ON public.call_log_debug IS '数据有效性约束：CHECK ((jsonb_typeof(exchanges_json) = ''array''::text))';
COMMENT ON CONSTRAINT call_log_debug_schema_version_check ON public.call_log_debug IS '数据有效性约束：CHECK ((schema_version = 1))';
COMMENT ON CONSTRAINT call_log_debug_pkey ON public.call_log_debug IS '主键：唯一标识仅在明确启用调试时保存的已脱敏请求与响应正文的记录  (call_id)';
COMMENT ON CONSTRAINT call_log_debug_call_id_fkey ON public.call_log_debug IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (call_id) REFERENCES public.call_log(id) ON DELETE CASCADE';
COMMENT ON INDEX public.call_log_debug_pkey IS '支撑主键 call_log_debug.call_log_debug_pkey';

-- 媒体输入的手工或连线来源，允许同一输入保留多个来源。
CREATE TABLE public.canvas_item_media_input_source (
    id uuid NOT NULL, -- 记录身份
    project_id uuid NOT NULL, -- 所属项目及授权作用域
    canvas_item_id uuid NOT NULL, -- 固定的目标或上下文画布卡片
    artifact_version_id uuid NOT NULL, -- 固定的不可变产物版本
    source_type character varying(16) NOT NULL, -- 媒体输入来自手工选择或画布连线
    connection_id uuid, -- 输入来源画布连线；手工来源为空
    created_at timestamp with time zone NOT NULL, -- 创建时间（UTC）
    CONSTRAINT ck_canvas_item_media_input_source_type CHECK (((((source_type)::text = 'MANUAL'::text) AND (connection_id IS NULL)) OR (((source_type)::text = 'CONNECTION'::text) AND (connection_id IS NOT NULL)))),
    CONSTRAINT canvas_item_media_input_source_pkey PRIMARY KEY (id),
    CONSTRAINT fk_canvas_item_media_input_source_connection FOREIGN KEY (connection_id) REFERENCES public.canvas_connection(id) ON DELETE CASCADE,
    CONSTRAINT fk_canvas_item_media_input_source_input FOREIGN KEY (project_id, canvas_item_id, artifact_version_id) REFERENCES public.canvas_item_media_input(project_id, canvas_item_id, artifact_version_id) ON DELETE CASCADE
);

COMMENT ON TABLE public.canvas_item_media_input_source IS '媒体输入的手工或连线来源，允许同一输入保留多个来源';
COMMENT ON COLUMN public.canvas_item_media_input_source.id IS '记录身份';
COMMENT ON COLUMN public.canvas_item_media_input_source.project_id IS '所属项目及授权作用域';
COMMENT ON COLUMN public.canvas_item_media_input_source.canvas_item_id IS '固定的目标或上下文画布卡片';
COMMENT ON COLUMN public.canvas_item_media_input_source.artifact_version_id IS '固定的不可变产物版本';
COMMENT ON COLUMN public.canvas_item_media_input_source.source_type IS '媒体输入来自手工选择或画布连线';
COMMENT ON COLUMN public.canvas_item_media_input_source.connection_id IS '输入来源画布连线；手工来源为空';
COMMENT ON COLUMN public.canvas_item_media_input_source.created_at IS '创建时间（UTC）';
COMMENT ON CONSTRAINT ck_canvas_item_media_input_source_type ON public.canvas_item_media_input_source IS '数据有效性约束：CHECK (((((source_type)::text = ''MANUAL''::text) AND (connection_id IS NULL)) OR (((source_type)::text = ''CONNECTION''::text) AND (connection_id IS NOT NULL))))';
COMMENT ON CONSTRAINT canvas_item_media_input_source_pkey ON public.canvas_item_media_input_source IS '主键：唯一标识媒体输入的手工或连线来源，允许同一输入保留多个来源的记录  (id)';
COMMENT ON CONSTRAINT fk_canvas_item_media_input_source_connection ON public.canvas_item_media_input_source IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (connection_id) REFERENCES public.canvas_connection(id) ON DELETE CASCADE';
COMMENT ON CONSTRAINT fk_canvas_item_media_input_source_input ON public.canvas_item_media_input_source IS '外键：保证引用存在并保持用户、项目或版本作用域一致；FOREIGN KEY (project_id, canvas_item_id, artifact_version_id) REFERENCES public.canvas_item_media_input(project_id, canvas_item_id, artifact_version_id) ON DELETE CASCADE';
COMMENT ON INDEX public.canvas_item_media_input_source_pkey IS '支撑主键 canvas_item_media_input_source.canvas_item_media_input_source_pkey';

CREATE UNIQUE INDEX uq_canvas_item_media_input_connection_source ON public.canvas_item_media_input_source USING btree (connection_id) WHERE ((source_type)::text = 'CONNECTION'::text);
COMMENT ON INDEX public.uq_canvas_item_media_input_connection_source IS '唯一索引：保证限定范围内不重复；USING btree (connection_id) WHERE ((source_type)::text = ''CONNECTION''::text)';

CREATE UNIQUE INDEX uq_canvas_item_media_input_manual_source ON public.canvas_item_media_input_source USING btree (project_id, canvas_item_id, artifact_version_id) WHERE ((source_type)::text = 'MANUAL'::text);
COMMENT ON INDEX public.uq_canvas_item_media_input_manual_source IS '唯一索引：保证限定范围内不重复；USING btree (project_id, canvas_item_id, artifact_version_id) WHERE ((source_type)::text = ''MANUAL''::text)';

-- 内置初始化数据：28 行；不包含用户业务数据或真实凭据。

INSERT INTO public.audit_debug_settings (id, debug_mode, version)
VALUES (1, false, 1);

INSERT INTO public.audit_log_retention_settings (id, retention_days, version)
VALUES (1, NULL, 1);

INSERT INTO public.media_provider_connection (id, name, enabled, version, current_version, created_at, updated_at, platform)
VALUES ('00000000-0000-4000-8000-000000000101', 'Mock', true, 0, 1, now(), now(), 'MOCK');

INSERT INTO public.media_provider_connection (id, name, enabled, version, current_version, created_at, updated_at, platform)
VALUES ('00000000-0000-4000-8000-000000000201', '本地图片处理', true, 0, 1, now(), now(), 'LOCAL');

INSERT INTO public.media_capability (id, connection_id, name, enabled, version, current_version, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000101', 'Mock image', true, 0, 1, now(), now());

INSERT INTO public.media_capability (id, connection_id, name, enabled, version, current_version, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000103', '00000000-0000-4000-8000-000000000101', 'Mock video', true, 0, 1, now(), now());

INSERT INTO public.media_capability (id, connection_id, name, enabled, version, current_version, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000202', '00000000-0000-4000-8000-000000000201', '本地图片处理', true, 0, 1, now(), now());

INSERT INTO public.media_capability (id, connection_id, name, enabled, version, current_version, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000104', '00000000-0000-4000-8000-000000000101', 'Mock audio', true, 0, 1, now(), now());

INSERT INTO public.media_capability_version (capability_id, version, adapter_id, mapping_sha256, spec_json, created_at)
VALUES ('00000000-0000-4000-8000-000000000102', 1, 'MOCK_IMAGE', '7f2f6b237c227a8fd7b0a2c12f59fdf5edbeda554d4fde5b602245e4a6edb1e2', '{"kind": "IMAGE_GENERATION", "schemaVersion": 1}', now());

INSERT INTO public.media_capability_version (capability_id, version, adapter_id, mapping_sha256, spec_json, created_at)
VALUES ('00000000-0000-4000-8000-000000000103', 1, 'MOCK_VIDEO', '17f04ba5441f7695f98e465cbacc8d7965d763d263762f464888158831434934', '{"kind": "VIDEO_GENERATION", "schemaVersion": 1, "maximumSeconds": 30, "minimumSeconds": 1}', now());

INSERT INTO public.media_capability_version (capability_id, version, adapter_id, mapping_sha256, spec_json, created_at)
VALUES ('00000000-0000-4000-8000-000000000202', 1, 'LOCAL_IMAGE_PROCESSOR', '4a733fddf137aaef35e919f050a6f5f4c5968fb9444c6689b775c3e365e25be9', '{"kind": "IMAGE_GENERATION", "settings": {}, "schemaVersion": 1, "maxReferenceImages": 1}', now());

INSERT INTO public.media_capability_version (capability_id, version, adapter_id, mapping_sha256, spec_json, created_at)
VALUES ('00000000-0000-4000-8000-000000000104', 1, 'MOCK_AUDIO', 'a99693d1b8d180ec693576125b0b513b22c88f0750d6ef9a70bfc01f8b2d47d5', '{"kind": "AUDIO_GENERATION", "schemaVersion": 1}', now());

INSERT INTO public.media_provider_connection_version (connection_id, version, origin, origin_sha256, credential_ciphertext, credential_nonce, credential_key_version, created_at, key_mask)
VALUES ('00000000-0000-4000-8000-000000000101', 1, NULL, NULL, NULL, NULL, NULL, now(), NULL);

INSERT INTO public.media_provider_connection_version (connection_id, version, origin, origin_sha256, credential_ciphertext, credential_nonce, credential_key_version, created_at, key_mask)
VALUES ('00000000-0000-4000-8000-000000000201', 1, NULL, NULL, NULL, NULL, NULL, now(), NULL);

INSERT INTO public.media_style (id, name, category, prompt_suffix, enabled, version, builtin_key, thumbnail_bytes, thumbnail_content_type, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000301', '写实摄影', '写实', 'Photorealistic photography, natural textures and realistic materials, balanced natural lighting, lifelike detail, refined color grading.', true, 0, 'photographic', NULL, NULL, now(), now());

INSERT INTO public.media_style (id, name, category, prompt_suffix, enabled, version, builtin_key, thumbnail_bytes, thumbnail_content_type, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000302', '电影质感', '写实', 'Cinematic visual storytelling, expressive film lighting, carefully composed frames, rich but restrained color grading, subtle film grain, realistic materials.', true, 0, 'cinematic', NULL, NULL, now(), now());

INSERT INTO public.media_style (id, name, category, prompt_suffix, enabled, version, builtin_key, thumbnail_bytes, thumbnail_content_type, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000303', '日系动漫', '插画', 'Japanese anime illustration, clean expressive linework, layered cel shading, delicate luminous colors, detailed painted backgrounds, coherent character design.', true, 0, 'anime', NULL, NULL, now(), now());

INSERT INTO public.media_style (id, name, category, prompt_suffix, enabled, version, builtin_key, thumbnail_bytes, thumbnail_content_type, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000304', '3D 动画', '3D', 'High-quality stylized 3D animation, appealing rounded forms, polished physically based materials, soft global illumination, playful expressive details.', true, 0, 'three-dimensional', NULL, NULL, now(), now());

INSERT INTO public.media_style (id, name, category, prompt_suffix, enabled, version, builtin_key, thumbnail_bytes, thumbnail_content_type, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000305', '水彩插画', '绘画', 'Watercolor illustration on textured paper, translucent washes, soft organic edges, delicate pigment blooms, gentle harmonious colors, hand-painted detail.', true, 0, 'watercolor', NULL, NULL, now(), now());

INSERT INTO public.media_style (id, name, category, prompt_suffix, enabled, version, builtin_key, thumbnail_bytes, thumbnail_content_type, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000306', '国风水墨', '绘画', 'Traditional Chinese ink-wash painting, expressive ink brushwork, elegant restrained color accents, rice-paper texture, poetic negative space, atmospheric layered washes.', true, 0, 'ink-wash', NULL, NULL, now(), now());

INSERT INTO public.media_style (id, name, category, prompt_suffix, enabled, version, builtin_key, thumbnail_bytes, thumbnail_content_type, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000307', '赛博朋克', '幻想', 'Cyberpunk visual aesthetic, luminous neon accents, futuristic urban design, rich electric blue and magenta color contrast, atmospheric haze, detailed metallic materials.', true, 0, 'cyberpunk', NULL, NULL, now(), now());

INSERT INTO public.media_style (id, name, category, prompt_suffix, enabled, version, builtin_key, thumbnail_bytes, thumbnail_content_type, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000308', '黏土定格', '3D', 'Handcrafted clay stop-motion aesthetic, tactile sculpted clay surfaces, charming miniature sets, softly rounded shapes, visible handmade imperfections, warm studio lighting.', true, 0, 'clay', NULL, NULL, now(), now());

INSERT INTO public.installation_lock (id)
VALUES (1);

INSERT INTO public.llm_provider_config_counter (id, current_version)
VALUES (1, 0);

INSERT INTO public.media_default (kind, capability_id, version)
VALUES ('IMAGE_GENERATION', '00000000-0000-4000-8000-000000000102', 0);

INSERT INTO public.media_default (kind, capability_id, version)
VALUES ('VIDEO_GENERATION', '00000000-0000-4000-8000-000000000103', 0);

INSERT INTO public.media_default (kind, capability_id, version)
VALUES ('AUDIO_GENERATION', '00000000-0000-4000-8000-000000000104', 0);

INSERT INTO public.storage_settings (singleton, version, active_profile_id)
VALUES (true, 0, NULL);
