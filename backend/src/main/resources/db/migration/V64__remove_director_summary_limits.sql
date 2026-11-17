-- Compaction follows a handoff-based history replacement; no independent summary
-- length target or fixed recent-tool tail participates in planning.
ALTER TABLE public.director_settings DROP CONSTRAINT director_settings_budgets;
ALTER TABLE public.director_settings DROP COLUMN summary_tokens, DROP COLUMN recent_tool_rounds;
ALTER TABLE public.director_settings ADD CONSTRAINT director_settings_budgets CHECK (
    context_window_tokens BETWEEN 8192 AND 2000000
    AND compaction_threshold_percent BETWEEN 1 AND 100
    AND response_reserve_tokens >= 1024 AND response_reserve_tokens < context_window_tokens / 2
    AND recovery_window_minutes BETWEEN 1 AND 10080
    AND max_model_retries BETWEEN 1 AND 10000
    AND retry_max_delay_seconds BETWEEN 2 AND 3600
    AND max_repair_attempts BETWEEN 1 AND 10 AND version >= 1);
COMMENT ON CONSTRAINT director_settings_budgets ON public.director_settings
    IS '上下文、模型响应与恢复参数必须满足运行时预算约束';
-- Invalidate settings drafts opened before the API lost the obsolete fields.
UPDATE public.director_settings SET version = version + 1;
