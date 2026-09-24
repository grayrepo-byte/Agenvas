-- Existing media/export entries predate the LLM dimensions in the public usage response.
UPDATE usage_ledger
SET quantity_json = jsonb_build_object(
        'llmRequestCount', 0,
        'inputTokens', NULL,
        'outputTokens', NULL
    ) || quantity_json
WHERE NOT (quantity_json ? 'llmRequestCount'
    AND quantity_json ? 'inputTokens'
    AND quantity_json ? 'outputTokens');
