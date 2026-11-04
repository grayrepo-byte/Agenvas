ALTER TABLE public.llm_provider_config ADD COLUMN model_policy jsonb;
ALTER TABLE public.llm_provider_config ADD CONSTRAINT llm_model_policy_object
    CHECK (model_policy IS NULL OR jsonb_typeof(model_policy) = 'object');
COMMENT ON COLUMN public.llm_provider_config.model_policy IS
    'Immutable administrator-confirmed model capacity, output parameter and metadata provenance; NULL means unknown legacy capacity';
COMMENT ON CONSTRAINT llm_model_policy_object ON public.llm_provider_config IS
    'Model policy is an object when present; legacy versions remain NULL';

ALTER TABLE public.director_settings ALTER COLUMN context_window_tokens SET DEFAULT 32768;
ALTER TABLE public.director_settings ALTER COLUMN response_reserve_tokens SET DEFAULT 4096;
ALTER TABLE public.director_settings ALTER COLUMN summary_tokens SET DEFAULT 1024;
-- Only the untouched initial built-in row changes; explicitly saved settings keep their version and values.
UPDATE public.director_settings
SET context_window_tokens = 32768, response_reserve_tokens = 4096,
    summary_tokens = 1024, version = version + 1
WHERE id = 1 AND version = 1 AND context_window_tokens = 1000000
  AND compaction_threshold_percent = 50 AND response_reserve_tokens = 8192
  AND summary_tokens = 2048 AND recent_tool_rounds = 4 AND recovery_window_minutes = 1440
  AND max_model_retries = 288 AND retry_max_delay_seconds = 300 AND max_repair_attempts = 4;
