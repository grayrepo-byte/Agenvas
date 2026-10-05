-- Keep the model response and timing/usage data; discard redundant public-delivery tracking.
ALTER TABLE public.call_log DROP CONSTRAINT call_log_llm_stream_metrics_check;
UPDATE public.call_log
SET llm_stream_metrics_json = (llm_stream_metrics_json - 'firstOutputMs' - 'outputBatchCount' - 'outputChars')
    || '{"schemaVersion":2}'::jsonb
WHERE llm_stream_metrics_json IS NOT NULL;
ALTER TABLE public.call_log ADD CONSTRAINT call_log_llm_stream_metrics_check
    CHECK (llm_stream_metrics_json IS NULL OR (jsonb_typeof(llm_stream_metrics_json) = 'object'
        AND llm_stream_metrics_json ? 'schemaVersion' AND llm_stream_metrics_json->>'schemaVersion' = '2'));
UPDATE public.call_log_debug
SET llm_stream_content_json = llm_stream_content_json - 'output'
WHERE llm_stream_content_json IS NOT NULL;
COMMENT ON COLUMN public.call_log.llm_stream_metrics_json IS '流式模型调用的首片段、首字、总耗时、实际用量与结束状态；不含前端进度或正文';
COMMENT ON CONSTRAINT call_log_llm_stream_metrics_check ON public.call_log IS '流式调用指标为空或 Schema 版本 2 的 JSON 对象';
COMMENT ON COLUMN public.call_log_debug.llm_stream_content_json IS '显式 debug 开关开启时汇总的单份脱敏模型公开响应；不保存展示输出、私有推理或原始 SSE';
