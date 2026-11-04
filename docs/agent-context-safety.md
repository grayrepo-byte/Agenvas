# Agent context capacity and incomplete output

## Match the configured budget to the serving model

Director Settings controls Agenvas's estimated context budget. It does not change the
context size of the remote model. The global default window is 1,000,000 tokens, with
8,192 reserved for a response. This is an application budget, not a detected model
capability. There is currently no separate context-capacity field per LLM endpoint.

Before using a local model, configure its actual serving context, then set the Director
Settings window no higher than that capacity. Also adjust the response reserve and
summary budget to fit that window: the reserve must be below half the window, and
the summary must fit within one quarter of the remaining input budget. Leaving the
default 8,192 response reserve while selecting a 16,384 window is invalid.

For Ollama's OpenAI-compatible API, context size is configured on the Ollama side.
Its [official compatibility guide](https://github.com/ollama/ollama/blob/main/docs/api/openai-compatibility.mdx#setting-the-local-context-size)
documents creating a model with `PARAMETER num_ctx` in its Modelfile, then selecting
that model name in the client. The Director Settings window must still be set
separately. Review the serving process's effective configuration and logs; a model's
advertised maximum context is not proof of the capacity currently allocated to it.

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
server's truncation warnings while repeating the same task. The application reserve
does not itself set the server's output-token limit.

Agenvas estimates messages, tool definitions, arguments and results with a shared
tokenizer and a safety margin. The estimate is not the model's exact tokenizer.
Fixed system instructions and tool definitions must fit even before useful history
can be compacted. If those fixed inputs do not fit, the run stops with
`AGENT_CONTEXT_BUDGET_EXCEEDED`; use a model/context large enough for that request,
or reduce optional instructions and Skill inputs. A 16K context passing one small
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
They establish that those responses have no tool side effects and cannot complete
successfully. They do not reproduce a particular model's silent input truncation,
measure a model's usable context, or establish Windows/WSL/Ollama compatibility.
Local HTTP fixtures additionally exercise the real SDK's uppercase `LENGTH`
metadata, incomplete parseable tool streams, valid finish-only/DONE-only streams,
and SDK framing boundaries. They do not establish compatibility with every proxy.
