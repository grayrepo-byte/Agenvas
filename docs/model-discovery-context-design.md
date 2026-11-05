# Model discovery and effective request budgets

The connection form owns provider selection, model discovery, automatic capacity
selection and explicit overrides. Manual IDs remain usable when discovery is
unsupported. Discovery never runs generation, downloads weights, saves a connection,
or establishes tool verification.

Providers: OpenAI-compatible, OpenAI, OpenRouter, Gemini, Anthropic and Ollama.
Generation keeps the existing Spring AI Chat Completions route; Gemini and
Anthropic use their documented compatibility endpoints. Provider-specific metadata
adapters preserve the configured prefix. Choosing Gemini/Ollama explicitly permits
only their documented sibling metadata paths, never arbitrary returned URLs.

Store raw theoretical/context/input/output limits separately from runtime context
observations and the administrator's effective selection. Every number is nullable
until supported by metadata or explicit manual configuration. Ollama model_info is
not allocated memory: automatic selection requires a matching loaded-model context,
and must never increase allocation to the architectural maximum. Runtime changes
must cause a safe failure rather than silently expanding or shrinking a frozen call.

Metadata preview has DRAFT and SAVED credential modes. Saved mode derives both
endpoint and credential from the same expected immutable version. Draft mode uses
only the explicitly submitted credential. A bounded short-lived server preview
binds accepted automatic values to that endpoint, provider, model and credential;
clients cannot relabel arbitrary values as detected. User overrides are labelled
manual. Preview does not modify encrypted configurations.

New connection versions persist the selected policy alongside the existing encrypted
credential. Unknown old policies stay unknown; no model capacity is invented during
migration. New requests require confirmed capacity; stored complete requests and
responses remain immutable. Replace the untouched built-in million-token default
with a 32768-token application ceiling (not a detected provider capacity), 4096-token
response reserve and 1024-token summary. Preserve explicitly saved director settings.

For each new request, effective context is bounded by the selected connection and
the global application ceiling. Effective output is bounded by the selected output
limit and reserve. Input and combined-context ceilings are checked separately. The
exact effective budget and output parameter are saved in the request envelope and
used by dispatch/retry/compaction/repair. Direct text and diagnostic paths enforce
the same connection policy. Fixed inputs that cannot fit fail before network calls;
history may be summarized, never silently clipped to claim success.

The metadata transport is separate from the exact-chat-POST transport. It reuses
origin/address validation, DNS pinning, no proxy/redirect/retry/debug capture, and
adds finite operation paths, concurrency, whole-call timeout, decompressed bytes,
pagination and model-count limits. Keys never appear in URLs, cache keys, logs or
browser persistence. Metadata strings never become prompts or network targets.

Implementation order: metadata parser and transport; immutable policy/API/migration;
request budgets and wire output controls; original-form workflow; comprehensive
targeted unit/HTTP/PostgreSQL/UI checks and local commits. No real service success
is claimed from synthetic fixtures; no push, deployment or host-network changes.

Protocol references: [Gemini models](https://ai.google.dev/api/models),
[Claude model metadata](https://platform.claude.com/docs/en/api/models/list),
[Claude compatibility](https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk),
[OpenRouter models](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties),
[Ollama show](https://docs.ollama.com/api/show),
[Ollama running models](https://docs.ollama.com/api/ps).
