ALTER TABLE public.call_log ADD COLUMN llm_stream_metrics_json jsonb;
ALTER TABLE public.call_log ADD CONSTRAINT call_log_llm_stream_metrics_check
    CHECK (llm_stream_metrics_json IS NULL OR (jsonb_typeof(llm_stream_metrics_json) = 'object'
        AND llm_stream_metrics_json ? 'schemaVersion' AND llm_stream_metrics_json->>'schemaVersion' = '1'));
COMMENT ON COLUMN public.call_log.llm_stream_metrics_json IS '流式模型调用的首片段、首字、首次公开输出、用量与结束状态；不含正文';
COMMENT ON CONSTRAINT call_log_llm_stream_metrics_check ON public.call_log IS '流式调用指标为空或 Schema 版本 1 的 JSON 对象';
ALTER TABLE public.call_log_debug ADD COLUMN llm_stream_content_json jsonb;
ALTER TABLE public.call_log_debug ADD CONSTRAINT call_log_debug_llm_stream_content_check
    CHECK (llm_stream_content_json IS NULL OR jsonb_typeof(llm_stream_content_json) = 'object');
COMMENT ON COLUMN public.call_log_debug.llm_stream_content_json IS '显式 debug 开关开启时汇总的脱敏模型公开响应与实际公开输出；不保存私有推理或原始 SSE';
COMMENT ON CONSTRAINT call_log_debug_llm_stream_content_check ON public.call_log_debug IS '流式调用正文为空或 JSON 对象';
