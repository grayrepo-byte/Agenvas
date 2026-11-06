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
observations and the administrator's effective selection. Missing provider metadata
stays unknown. When capacity is unavailable, discovery fails, a model ID is entered
manually, or a legacy connection without a capacity policy is opened for editing,
the form prefills a 1,000,000-token context, leaves the input limit empty and clears
the automatic preview. This fallback preserves the draft's output limit; new forms
start with the existing 4,096-token output default.
Saving these editable generic defaults uses the existing MANUAL provenance; they
are not detected or measured provider capacity. Preserve explicit manual values.
Reliable metadata still selects AUTO, including input-only Gemini metadata without
inventing a combined context limit. Ollama model_info is not allocated memory:
automatic selection requires a matching loaded-model context and must never increase
allocation to the architectural maximum. The manual default remains available when
no loaded runtime is reported, without changing server allocation. Runtime changes
must cause a safe failure for automatic policies rather than silently expanding or
shrinking a frozen call.

Metadata preview has DRAFT and SAVED credential modes. Saved mode derives both
endpoint and credential from the same expected immutable version. Draft mode uses
only the explicitly submitted credential. A bounded short-lived server preview
binds accepted automatic values to that endpoint, provider, model and credential;
clients cannot relabel arbitrary values as detected. User overrides are labelled
manual. Preview does not modify encrypted configurations.

New connection versions persist the selected policy alongside the existing encrypted
credential. For new requests, an existing capacity policy takes precedence. When no
policy exists, including legacy database connections and environment configuration,
resolve a generic 1,000,000-token context, 4,096-token maximum output and MAX_TOKENS
(`max_tokens`) parameter locally. No metadata request or connection resave is needed
to resolve this default, and it is not a provider capacity claim. Existing Ollama
runtime validation still applies. The default global application ceiling remains
32768 tokens, with a 4096-token response reserve and 1024-token summary; effective
budgets use the smaller resolved connection and application limits.

Runtime default resolution does not write a policy into legacy database connections,
change director settings or require an API or data migration. Frozen request budgets
and responses remain immutable. Historical recovery without a frozen budget is not
backfilled with the default, and failed old tasks are not resubmitted automatically.

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
