# 0069 — Model discovery and confirmed request capacity

Status: accepted. Refines the default capacity assumptions in ADR 0063 and keeps ADR 0067/0068 network boundaries.

## Decision

Use explicit provider metadata adapters in the existing model connection form. Keep IDs and limits manually configurable. Store confirmed limits and provenance on immutable connection versions. Missing provider metadata remains unknown; ID discovery alone cannot certify model capacity. Automatic preview is bounded, short-lived and bound to configuration scope. No model weights are downloaded.

Replace only untouched million-token application defaults with 32768/4096/1024 context/output-reserve/summary ceilings. Preserve custom settings and legacy versions. New requests require confirmed capacity and freeze effective limits through compaction, repair, retry and direct text work. Send one explicit supported output-token parameter. Preserve current input and fail safely when fixed content cannot fit.

Ollama architecture limits are distinct from loaded runtime capacity. Observe and preflight the loaded context without allocating the maximum or modifying host configuration. Native `num_ctx` control, automatic model loading and universal provider context-error recovery are outside this increment; manual configuration and actionable errors remain available.

## Manual form defaults — 2026-11-05 revision

The user chose a generic 1M connection-form default to reduce manual entry when
provider capacity cannot be obtained. When capacity is absent, discovery fails, a
model ID is entered manually, or a legacy connection without a capacity policy is
edited, prefill a 1,000,000-token context, leave input empty and clear the automatic
preview. Preserve the draft's current output limit; new forms keep the existing
4,096-token output default. Save the editable defaults with the existing MANUAL
provenance, never as provider-detected
capacity. Preserve explicit manual values. This replaces the previous requirement
to fill missing capacity from scratch before saving.

Reliable metadata continues to select AUTO, including input-only Gemini metadata
without adding a combined context limit. Ollama automatic selection still requires
a matching loaded runtime; the manual 1M default is allowed without one and does not
change server allocation. Opening or reading the form does not rewrite saved
connections. The director's global default ceiling remains 32768, and saved director
settings remain unchanged. This is a form-only change with no backend, API or data
migration.

## Consequences

Upgrades need a one-time capacity confirmation for new work using old stored or environment-only connections. Existing frozen envelopes retain prior behavior. Provider metadata can be absent, stale or describe routing rather than measured capacity. Token estimates and Ollama preflight cannot guarantee absence of upstream silent truncation. Generation retains Spring AI's existing compatible chat protocol; native provider-only features are not implied.

See [design](../model-discovery-context-design.md) and [operating guidance](../agent-context-safety.md).
