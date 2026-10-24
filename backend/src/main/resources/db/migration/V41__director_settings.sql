CREATE TABLE public.director_settings (
    id integer NOT NULL,
    context_window_tokens integer NOT NULL DEFAULT 1000000,
    compaction_threshold_percent integer NOT NULL DEFAULT 50,
    response_reserve_tokens integer NOT NULL DEFAULT 8192,
    summary_tokens integer NOT NULL DEFAULT 2048,
    recent_tool_rounds integer NOT NULL DEFAULT 4,
    recovery_window_minutes integer NOT NULL DEFAULT 1440,
    max_model_retries integer NOT NULL DEFAULT 288,
    retry_max_delay_seconds integer NOT NULL DEFAULT 300,
    max_repair_attempts integer NOT NULL DEFAULT 4,
    version bigint NOT NULL DEFAULT 1,
    CONSTRAINT director_settings_pkey PRIMARY KEY (id),
    CONSTRAINT director_settings_singleton CHECK (id = 1),
    CONSTRAINT director_settings_budgets CHECK (
        context_window_tokens BETWEEN 8192 AND 2000000
        AND compaction_threshold_percent BETWEEN 1 AND 100
        AND response_reserve_tokens >= 1024 AND response_reserve_tokens < context_window_tokens / 2
        AND summary_tokens BETWEEN 256 AND (context_window_tokens - response_reserve_tokens) / 4
        AND recent_tool_rounds BETWEEN 0 AND 16
        AND recovery_window_minutes BETWEEN 1 AND 10080
        AND max_model_retries BETWEEN 1 AND 10000
        AND retry_max_delay_seconds BETWEEN 2 AND 3600
        AND max_repair_attempts BETWEEN 1 AND 10 AND version >= 1)
);
COMMENT ON TABLE public.director_settings IS '管理员维护的全局导演上下文、压缩与恢复设置；新请求读取当前值，已有检查点不改写';
COMMENT ON COLUMN public.director_settings.id IS '固定为 1 的单例身份';
COMMENT ON COLUMN public.director_settings.context_window_tokens IS '模型上下文窗口的估算 Token 预算';
COMMENT ON COLUMN public.director_settings.compaction_threshold_percent IS '按完整窗口计算的自动压缩触发百分比';
COMMENT ON COLUMN public.director_settings.response_reserve_tokens IS '响应预留 Token，不是供应商输出参数';
COMMENT ON COLUMN public.director_settings.summary_tokens IS '交接摘要的 Token 上限';
COMMENT ON COLUMN public.director_settings.recent_tool_rounds IS '压缩时最多保留的完整近期工具回合';
COMMENT ON COLUMN public.director_settings.recovery_window_minutes IS '从首次临时模型失败开始的恢复期限分钟数';
COMMENT ON COLUMN public.director_settings.max_model_retries IS '同一模型请求首次调用以外的最多重试次数';
COMMENT ON COLUMN public.director_settings.retry_max_delay_seconds IS '模型失败指数退避的最大秒数';
COMMENT ON COLUMN public.director_settings.max_repair_attempts IS '同一工具参数或摘要错误链的最多修复次数';
COMMENT ON COLUMN public.director_settings.version IS '管理员保存设置时的乐观锁版本';
COMMENT ON CONSTRAINT director_settings_pkey ON public.director_settings IS '导演设置单例主键';
COMMENT ON CONSTRAINT director_settings_singleton ON public.director_settings IS '限制为全局唯一设置记录';
COMMENT ON CONSTRAINT director_settings_budgets ON public.director_settings IS '上下文、响应、摘要与恢复参数必须满足运行时预算约束';
INSERT INTO public.director_settings (id) VALUES (1);
