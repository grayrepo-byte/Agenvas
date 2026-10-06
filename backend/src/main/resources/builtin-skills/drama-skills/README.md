# Agenvas Drama Skills — canvas edition

Based on: https://github.com/zenstory-ai/drama-skills
Upstream version: 0.8.1
Upstream commit: c2426e03c0e7722bebcc6a488b6658dc38c65ac3
Agenvas edition: agenvas-canvas-v1
License: MIT; the original LICENSE is included without changes.

This is an adapted creative collection. Its eight SKILL.md files describe Agenvas input cards, immutable source versions, text creation/revision, canvas outputs, resumable progress and the existing media approval tools. Useful craft references are retained and their operational assumptions adapted. The packaged content is no longer a byte-for-byte upstream vendor copy.

The stages are novel analysis, story development, screenplay writing, visual asset design, image prompts, storyboards, video prompts and review. The filesystem/Dashboard router, external production and editing Skills are excluded. Shell commands, Python tools, machine-oriented runtime/agent configuration, evaluations and filesystem publication templates are not packaged or referenced by model-visible content.

Each Skill has one native Markdown card template and the shared references/agenvas-cards.md workflow, loaded on demand. Across the eight bundles there are 112 attachment entries: 96 creative references, eight card templates and eight references to the same shared workflow file. There are 113 physical Markdown files (eight mains, 104 stage-specific attachments and one shared workflow). manifest.json enumerates all bundle resources. BuiltinSkillCatalogue resolves the reserved shared path from this package root.

Chapter indexing and coverage are represented by source, index, chapter-analysis and coverage/progress cards. Coverage is an Agent content review of actually read immutable versions, with explicit missing, duplicate, stale and unverified entries; it is not a server-side automatic parser or validator. read_artifacts can return a truncated preview, so long inputs must currently be split into readable chapter/scene cards by the user.

Edition-qualified keys create new immutable registrations for this canvas collection. Existing upstream-style versions, bindings, installations and Run snapshots are preserved for historical reading and export. They are not selectable for new work. Agents configured with an old edition must select the current canvas Skills; no silent replacement of fixed selections occurs. The current manifest remains the authority for selectable built-in keys.

To update this collection, review the pinned source and license, edit the native card instructions and appropriate craft references, then validate every model-visible file for unsupported commands and dangling resource links. A change to an already distributed edition requires an explicit version/edition decision; replacing package files must not rewrite existing publications. Runtime and upgrade decisions are documented in ADR 0036.
