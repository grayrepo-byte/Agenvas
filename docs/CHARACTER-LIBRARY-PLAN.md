# Character Library Implementation Plan

> **For agentic workers:** Execute task-by-task with focused implementation and independent spec/quality review. User has already authorized implementation; routine design choices are decided in this plan.

**Goal:** Deliver account-owned reusable character assets and real canvas character nodes with explicit frozen project use.

**Architecture:** A private account library owns independently archived media and immutable character revisions. Project imports copy media and retain an explicit library source revision; canvas nodes reference fixed project profiles and use the existing character preview/apply pipeline.

**Tech Stack:** Spring Boot, jOOQ, PostgreSQL, Vite/React, TanStack Query, React Flow, existing shadcn components.

**Spec:** docs/CHARACTER-LIBRARY-DESIGN.md

## Global Constraints

- No historical data compatibility required. Add Flyway migrations; do not alter production data, push or deploy.
- Generated OpenAPI TypeScript and jOOQ code must be generated with official commands, never hand-edited.
- Account and project authorization apply on every endpoint. Network/file work must not hold long database transactions.
- Profile revisions and Task inputs are immutable; projects update only by explicit manual sync.
- No reference video, training, team sharing, visual look generation or unsupported native subject promises.
- Run scoped functional tests and real PostgreSQL transaction/concurrency checks; no automatic full-suite baseline.
- Commit author grayrepo-byte; no Codex attribution.

## Review Focus

- A source project or ordinary library entry is deleted while a character export/import is pending.
- Two requests update the same profile or sync the same project; one must conflict or replay safely.
- A transport failure occurs after commit; explicit replay must return the prior result without duplicate copies.
- A role node is copied, deleted, locked, archived or changed after media preview.
- A video model accepts first frames but no general identity references; character application must not mislabel the image.

### Task 1: Account character aggregate and independent transfer

**Files:** character library application/domain/infrastructure/api; V50 migration; OpenAPI library schemas/endpoints; focused backend tests.
**Interfaces:** Account character content with independent image/audio identities, immutable revisions; publish/import/sync commands with frozen revisions, idempotency/CAS and readable status. Exact interface details are finalized in the contract before frontend wiring.

- [x] Write/run a failing cross-account and fixed-project import test.
- [x] Implement independent media archival and durable transfer, profile CRUD/archive and explicit sync preview/apply through CharacterService.
- [x] Run focused unit and PostgreSQL tests, including failure/replay and concurrency.

### Task 2: Canvas character node and explicit relationship

**Files:** canvas application/domain/persistence/API, character node application models, V51 migration, OpenAPI canvas schemas/endpoints; focused tests.
**Interfaces:** CHARACTER subject, fixed project profile version/look/reference selection, node CAS; role relationship preview/apply delegates to CharacterService, without automatic generation.

- [x] Write/run a failing node-create and stale-selection test.
- [x] Implement persistence, authorization, lock/CAS, copy/remove/export/SSE and capability-safe connection behavior.
- [x] Run node/connection/character scoped tests on PostgreSQL.

### Task 3: Library and project flows

**Files:** features/characters library UI/query/client, library route/tab integration, project CharacterManagerDialog, locale messages and focused tests.
**Interfaces:** Generated Task 1 contracts; existing project editor and local/canvas reference pickers.

- [x] Write/run failing tests for library create/import and manual sync failure preservation.
- [x] Implement account library browsing/editor, independent media selection/upload, publish existing profile, project import and explicit sync preview.
- [x] Run scoped Vitest, typecheck, i18n/lint and production build.

### Task 4: Canvas UI and verification

**Files:** character node UI, ProjectWorkspacePage integration, relationship selection/preview and focused tests; schema generation and docs/ADR/checklist.
**Interfaces:** Generated Task 2 contracts; normal canvas projection plus fixed role selection.

- [x] Write/run failing character projection/create/connect interaction tests.
- [x] Implement node creation, look/reference editing, explicit application, safe relationship deletion and loading/error handling.
- [x] Generate schema/jOOQ against an isolated PostgreSQL container; run focused regression checks.
- [x] Independently review spec compliance and quality, fix material findings, record limitations and commit locally.
