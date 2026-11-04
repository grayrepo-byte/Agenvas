# Agent context capacity and incomplete output

## Match the configured budget to the serving model

The existing model connection form can read a model list explicitly and fill limits
when the selected provider reports reliable metadata. Select the provider profile,
enter its compatible chat base URL and key, then choose **Read model list**. This is
metadata traffic only: it does not save settings, download weights, generate content
or verify tools. The model ID remains editable. Missing limits stay unknown; confirm
them with the serving operator and use **Use manual values** when necessary.

The form distinguishes theoretical context, provider input/output limits and observed
Ollama loaded context. Automatic values are bound to the endpoint, credential, model
and configuration version in a ten-minute preview. Editing their scope requires a
fresh read; manual overrides remain manual. Model metadata is provider-reported,
not a measured guarantee. OpenAI and generic compatible lists normally provide IDs
without capacity: these profiles require manual confirmation. Gemini/Anthropic
metadata may provide separate input and output limits. Their generation still uses
the existing documented OpenAI compatibility endpoint, with its compatibility limits.

Director Settings supplies an application ceiling, not the remote model's context.
The default is now 32,768 tokens, response reserve 4,096 and summary 1,024. Each new
request uses the smaller connection/application limits, with independent input
limits and a 25% estimation margin. When only an input limit is known, it is checked independently; the combined
ceiling remains the application setting, with no provider context claim invented.
The selected output limit and reserve determine the actual `max_tokens` or
`max_completion_tokens` sent to the API. Choose the parameter supported by the
specific compatible deployment; reading a model ID does not establish that support.

Migration changes only the untouched initial million-token default settings. Saved
custom settings and old immutable connection versions are preserved. Legacy versions
with no confirmed capacity cannot start new model requests: edit and save the model
connection with confirmed limits first. Existing complete frozen requests/responses
retain their original envelope; they are not rewritten to pretend capacity was known.

Ollama `/api/show` advertises model capability; `/api/ps` describes loaded context.
Automatic capacity needs the exact loaded model (and matching digest when present).
Agenvas never sets `num_ctx` from the architectural maximum, pulls or warms a model,
or changes host networking. Configure/load the intended alias on the Ollama side,
then reread metadata. Before generation, an automatic Ollama policy is checked again;
an unloaded, smaller or replaced runtime stops with
`MODEL_RUNTIME_CONTEXT_CHANGED`. Temporary metadata contention, timeout or HTTP
429/5xx follows the existing bounded Agent connection recovery path before any
generation call; direct tasks report a connection failure for explicit retry.
This is a preflight, not an atomic reservation:
Ollama may still unload or change a model between this check and generation.
Manual mode remains available when a deployment cannot expose native metadata.

For a focused retest of the reported `qwen2.5:14b` scenario, the following is an
illustrative 16K configuration. Save this as `Modelfile.agenvas` on the Ollama host:

```dockerfile
FROM qwen2.5:14b
PARAMETER num_ctx 16384
```

Then run `ollama create agenvas-qwen-16k -f ./Modelfile.agenvas` and select
`agenvas-qwen-16k` as the model in Agenvas. In Director Settings, use the following
values together (leave recovery settings as appropriate for the installation):

| Setting | Example value |
| --- | ---: |
| Context window | 16,384 |
| Compaction threshold | 50% |
| Response reserve | 2,048 |
| Summary budget | 512 |
| Recent tool rounds | 1 |

This leaves an estimated 14,336-token hard input budget; fixed instructions can
exceed the soft compaction threshold when there is no history to compress. These
values are a retest starting point, not a measured minimum or a guarantee that a
larger task fits. Confirm the alias's effective serving context and inspect the
server's truncation warnings while repeating the same task. The application reserve and selected connection output limit now bound the
API output-token parameter; neither changes the server's allocated input context.

Agenvas estimates messages, tool definitions, arguments and results with a shared
tokenizer and a safety margin. The estimate is not the model's exact tokenizer.
Fixed system instructions and tool definitions must fit even before useful history
can be compacted. If those fixed inputs do not fit, the run stops with
`AGENT_CONTEXT_BUDGET_EXCEEDED`; use a model/context large enough for that request,
or reduce attachments, optional instructions and Skill inputs, or lower the output
reserve where appropriate. The current user input is not silently clipped to fit. A 16K context passing one small
scenario does not establish a supported minimum for every Agent workflow.

New requests use the current Director Settings. Retries of an already frozen request
retain its checkpoint and do not adopt a newly edited budget. After correcting a
capacity mismatch, inspect existing results and start a new run explicitly.

## Explicit output truncation

A response whose selected generation reports `finishReason=length` is incomplete,
even if its text says the task is complete or its tool arguments happen to parse.
Agenvas preserves that response checkpoint, then stops the Agent turn with
`LLM_OUTPUT_TRUNCATED` before executing any of its tools or reporting a successful
turn. The same check applies to restart/replay and context-summary responses.
This is a non-retryable configuration/output limit; the runtime does not repeatedly
resubmit the same request or use malformed-output repair to work around it.
Already committed results from earlier turns are retained. Public text streamed
before the final stop reason arrived may remain visible alongside the failed state.

This check detects an explicit token-limit stop signal. It cannot reliably detect a
server silently dropping input and returning an ordinary successful stop reason.
OpenAI-compatible prompt-token usage alone does not show which input text was
discarded or prove that truncation occurred. A successful tool diagnostic verifies
its small probe, not the capacity or semantic correctness of a full Agent task.
Likewise, a successful run means the orchestration completed; model-generated
content still needs review against the user's requirements.

## Incomplete transport streams

For a successful HTTP response declared as `text/event-stream`, or actual SSE data
fields received by a streaming request even with a missing or incorrect media type, Agenvas requires
a dispatched standalone `[DONE]` event or a nonempty string `finish_reason` on
choice index 0. A clean HTTP EOF alone cannot finish a tool call, even when its
arguments already parse. Missing completion signals stop the request with
`LLM_STREAM_INCOMPLETE`, before the response checkpoint or any tools from that
turn are accepted. This failure is not automatically retried or sent through
malformed-output repair; after checking the server or proxy, the user can
explicitly start a new run. Earlier committed results are retained, and already
streamed public text may remain alongside the failed state.

The observer uses the same stream with the existing byte bound and does not wait
for the whole body before forwarding it. It does not impose SSE completion rules
on JSON responses or HTTP errors. This detects missing transport/protocol
termination, not silent input truncation or semantic mistakes. A provider can
still return a valid completion signal after discarding part of its input.

## Verification scope

Regression tests use synthetic responses with explicit token-limit metadata,
including parseable tool calls and success prose, on both fresh and replayed turns.
Metadata, pagination, request budgets, output fields and runtime changes are tested
with bounded synthetic HTTP/SDK fixtures; permissions and migration use PostgreSQL.
They establish that those responses have no tool side effects and cannot complete
successfully. They do not reproduce a particular model's silent input truncation,
measure a model's usable context, or establish Windows/WSL/Ollama compatibility.
Local HTTP fixtures additionally exercise the real SDK's uppercase `LENGTH`
metadata, incomplete parseable tool streams, valid finish-only/DONE-only streams,
and SDK framing boundaries. They do not establish compatibility with every proxy.
