# Pose and Costume Reference Catalog Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for integration and isolated parallel agents for backend and frontend ownership. Test new behavior before implementation.

**Goal:** Browse grouped poses and categorized costumes, preview and import one exact image into a project, and manually synchronize authorized public GitHub packs.

**Architecture:** Independent reference-catalog domain; immutable GitHub commit + manifest/file hashes; project-owned imported media. Reuse existing media application interfaces and editor reference semantics. No third-party gallery contents are bundled or fetched.

**Tech Stack:** Spring Boot, jOOQ/PostgreSQL/Flyway, OpenAPI, React/TanStack Query/shadcn, Vitest/JUnit.

**Spec:** User-delegated implementation following the read-only research delivered in this thread; concrete source contract in docs/reference-packs.md. User subsequently selected hongye007/open-pose-costume and requested the same configurable-source/manual-sync/local-cache interaction as third-party prompts.

This plan records the initial `b5486b8` slice. The explicit image-file-cache follow-up
supersedes its metadata/link-only limits and deferred audio entry issue; see
[REFERENCE-CACHE-PLAN.md](REFERENCE-CACHE-PLAN.md) and ADR 0076 for current behavior.

## Global Constraints

- Four UI languages; no new runtime dependencies without need.
- No third-party restricted images, repository creation, push, deployment, or production database changes.
- Image inputs are ordinary references, never promises of pose/control or automatic video first frames.
- Sources managed by admins, project imports owner-authorized; recovery mode denies writes.
- Retain fixed project media and accepted inputs when a source changes or is disabled.
- Reserve V50/V51 for parallel character-library work; use V52 or later for this feature.
- Commit author grayrepo-byte. Do not edit another task's workspace.

## Review Focus

- Hash/size/decoder validation must reject unexpected or hostile remote files.
- Unknown/no redistribution license must not enable download by assertion from remote content alone.
- Lost import acknowledgement reuses one fixed command, never duplicates project media.
- Draft changes during import preserve user input and honor CAS.
- Mutable branches, missing sources and failed sync retain a usable previous snapshot.

## Task 1: Backend catalog and safe pack synchronization

**Files:** backend referencecatalog module, V52 migration, generated jOOQ, security guards and focused tests.
**Interfaces:** Read catalog sources/entries/variant preview; admin add/manual sync with version; project import fixed snapshot+variant+command key returning independent project version.
- [x] Write and run failing unit and PostgreSQL tests for validation, authorization, snapshots, replays and failure recovery.
- [x] Implement bounded public GitHub transport, immutable manifest validation, durable catalog persistence and owner-authorized import through existing media services.
- [x] Run focused tests and compile; report exact DTO contract to integration owner.

## Task 2: Frontend catalog and reference integration

**Files:** frontend reference-catalog feature and API module, minimal library/canvas/menu entry points, four locales and tests.
**Interfaces:** Backend catalog DTOs from Task 1; editor onApply receives exact imported IMAGE version and preserves prompt/parameters.
- [x] Write/run failing UI tests for grouping/filtering/preview, unavailable source, import retry and no automatic generation.
- [x] Implement shared catalog page/picker, explicit source manager/manual sync, and append-only image reference selection.
- [x] Run focused tests, typecheck and lint; do not hand edit generated schema.

## Task 3: Contract, self-owned sample pack and integration

**Files:** contracts/openapi.yaml, generated TypeScript, docs/reference-packs.md, examples/reference-pack and design/checklist docs.
**Interfaces:** Exact implemented backend DTOs and frontend requests; sample pack follows validated schema.
- [x] Create deterministic original geometric PNG fixtures and checksum-complete pose/costume pack without third-party data.
- [x] Describe pack format/rights and update authoritative API; generate TS types.
- [x] Check browser entry flows, run relevant regression suites and full compile/typecheck/lint; full suite remains manual per AGENTS.md.
- [x] Obtain independent branch review; fix important findings and verify.
- [x] Commit reviewed changes locally and report commits, evidence, limits and remaining integration conflicts.

## Implementation decisions

- Native worktree creation returned unavailable; an independent local clone under this task workspace provides isolation.
- OPEN_POSE_COSTUME reads public/data/catalog.json at a fixed Git commit. Cache metadata only; existing upstream/custom image rights are not independently granted to Agenvas, so this format remains link-only.
- AGENVAS_REFERENCE_PACK supports explicitly authorized images with file hashes and independent project copies; the sample contains only locally generated geometric test drawings.
- Raw catalog snapshots used for research stay outside the repository; tests use synthetic records following the real schema.
- Existing GitHub API limits can prevent branch resolution; explicit commit SHAs remain supported and failed sync preserves the previous snapshot.
- Plans under docs/superpowers are ignored by repository policy; this tracked root-level plan carries delivery status without force-adding ignored files.

## Verification and handoff

- Backend: 43 focused unit tests and 10 isolated PostgreSQL checks passed; jOOQ regenerated from an empty isolated database.
- Frontend: 251 related tests passed; the final catalog subset (16) passed after layout fixes. TypeScript, ESLint, theme colors, four-language catalogs and production build passed under Node 24.
- Production browser fixture passed at 2560×1179, 1440×900, 390×844 and 844×390. It checks visible card previews, complete scrollable images, fixed actions, actual selected-view import, metadata-only image gating and source settings.
- Browser geometry assertions first reproduced collapsed card previews and a shrinking preview container, then passed after content-sized buttons and nonshrinking image frames.
- Actual pinned upstream metadata was acquired without images and parsed into 3144 pose/costume entries and 4515 variants. Raw source data remains outside Git. The live Java client's fixed-host/public-DNS path correctly rejected this host's proxy/test DNS answers; no SSRF exception was added.
- Independent review found no blocking correctness/security issue. Deferred minor: the AUDIO editor still exposes the catalog for browsing although applying images there is unavailable; unsupported workflow slots show a disabled item.
- Existing nonblocking output: large production chunks and an existing missing MSW queue fixture in an UNKNOWN replacement test. Full suite, paid generation, live cloud-storage crash testing and combined character-library integration were not run.
- Keep this branch isolated from character-library commit cd7f2c1. Combine both migration sets and regenerate shared OpenAPI TypeScript/jOOQ after integration. Review the four shared asset recovery files carefully.
