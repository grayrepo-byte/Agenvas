# Agent context on demand

Goal: reduce fixed instructions and tool schema sent for ordinary conversation while retaining every authorized capability, custom creative instruction and recovery rule. No token discrepancy alert, HTTP policy change, dependency upgrade or deployment.

## Decision

Use explicit `load_toolset` discovery, not intent classification. A short immutable core and a tool-group directory remain present. The Director instruction and available Skill names/descriptions/versions remain verbatim. Media capability details move to the existing `list_media_capabilities` tool. Requested group schemas become available only in the next model request; the corresponding professional rules are added then. A model may load several groups in one call and add groups later as the task develops. Loaded groups remain available for this Run.

Alternatives: keyword routing saves a round but can miss indirect/multilingual tasks; an extra classifier adds cost and a failure point; only trimming prose leaves most tool schema cost. Explicit discovery trades an extra model/tool round on business tasks for predictable availability and very small initial inputs. This is an implementation choice within the authorized optimization scope.

## Frozen state and authority

The Run's existing allowedTools is the ceiling. Only newly created Runs containing `load_toolset` use discovery. Existing Runs keep their original prompt and full definitions. Each saved model request is the exact frozen toolset and rule checkpoint. A continuation extends the prior request's definitions only from successful committed load replies, intersected with the Run ceiling. Repairs keep the rejected request's tools and rules; canceled or rolled-back batches activate nothing. Retries and restart replay saved requests. Compaction carries the loaded definitions and pinned core/professional instructions in its durable continuation; summaries cannot load tools.

The executor also checks the recorded request's exposed tool names before any new effect. Loading and using a hidden tool in the same batch is rejected atomically. Permission, CAS, locks, authenticated media approval and UNKNOWN semantics remain server-enforced. Loading never grants object access or approval.

## Work and verification

- [x] Measure actual assembly using synthetic data: raw JTokkit components and application estimate including overhead and 25% margin. Preserve the existing English default and separately note older/custom instructions.
- [x] Add discovery/continuation and missing-tool execution cases. Implement bounded group loading and keep existing full-definition API for frozen legacy Runs.
- [x] Wire first request, continuation, repair and prompt selection; remove eager media summary only for discovery Runs.
- [x] Verify simple conversation, multiple group loads, complex storyboard/media/composition, task changes, permission ceiling, atomic rollback, repair, retry, restart and compaction at request and durable executor boundaries. Use real PostgreSQL for transaction/recovery tests.
- [x] Run focused existing regressions; no full baseline/full suite per AGENTS.md. Report synthetic/mock separately from real provider tests.
- [x] Independent standards/spec review, fix material findings, rerun relevant checks, update ADR/spec/checklist and commit locally. Do not push, merge or deploy.

Review focus: a failed load must not activate tools; a load cannot widen a frozen allowlist; a saved request cannot silently acquire live schemas; compaction must not erase active tools/rules; user instructions, Skill methods and media approvals must remain intact.
