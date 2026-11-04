# Model tool diagnostics

Model settings diagnostics are an administrator action with explicit cost acknowledgement. They send at most two calls to the selected, versioned OpenAI-compatible configuration. The probe accesses no project data and cannot execute application tools.

The first response must request exactly one `agenvas_connection_probe` call with a `function` type, nonempty bounded call ID and an object containing only the exact random `nonce`. The server supplies a separately generated `proof` through the tool result. The second response must contain that exact proof, allowing only surrounding whitespace, with no further tool call. A plain chat response, wrong tool/nonce, ignored tool result or extra explanatory text does not verify tool support. This tests a small tool round trip; it does not certify the serving context capacity or guarantee correctness on Agent tasks.

Protocol failures retain the API category `PROVIDER_TOOL_PROTOCOL_UNVERIFIED`; their translated detail identifies the failed stage. Each actual call appears in Call logs as **text model · Tool diagnostic**, under **System diagnostic**, without a project link. Safe metadata includes the model, local Trace ID, time, duration, status and a bounded error code:

- `PROVIDER_DIAGNOSTIC_NO_TOOL`: the first response did not contain exactly one probe call.
- `PROVIDER_DIAGNOSTIC_INVALID_TOOL`: the tool name, call ID or exact nonce/schema was invalid.
- `PROVIDER_DIAGNOSTIC_PROOF_MISMATCH`: the second response did not return only the proof.
- `PROVIDER_DIAGNOSTIC_EMPTY_RESPONSE`: there was no usable assistant response.
- `LLM_OUTPUT_TRUNCATED`: the provider explicitly stopped because of a token limit.
- `PROVIDER_DIAGNOSTIC_UNAVAILABLE`: a transport/model call failed; the response identifies which call failed. Typed application failures may retain their existing safe code.

A failed first call prevents the second. A successful first log with a failed second log is an unverified overall diagnostic. No model request is retried automatically, and already verified unchanged configuration does not issue another probe. A configuration changed during the probe cannot be marked verified by the stale result.

The normal list contains metadata only. Existing default-off administrator debug settings govern actual HTTP body capture for new diagnostic calls, with the same header protection, content bounds, `no-store` detail endpoint and cache cleanup as other model calls. Diagnostics are global configuration activity: their list and private debug details are available only to the active local administrator. Project calls remain scoped to the project's owner. No diagnostic row is attached to a fabricated project, Run or Task.

Migration V44 permits a null project identity only for system LLM diagnostics and rejects unscoped business calls. It does not change existing data. An administrator's explicit retention cleanup also deletes expired diagnostic call rows and their debug bodies; each diagnostic call counts as one audit unit. An interrupted process may leave a call without a completion record. After the existing per-call transport deadline plus connection grace (currently 610 seconds), the list and UNKNOWN filter project that incomplete diagnostic as UNKNOWN; the stored row is not changed, and no completed response or automatic retry is inferred. Recent in-flight diagnostic rows remain RUNNING.

To investigate a failed probe, first read its stage and error code. Confirm the endpoint and serving model, tool support, and the model server's context/output configuration. Enable debug only when actual request/response bodies are needed, then explicitly rerun after considering potential cost. In particular, an OpenAI-compatible API cannot reliably disclose Ollama's silent input truncation: inspect the model server's logs and serving configuration as well.
