# Image Studio Implementation Plan

> **For agentic workers:** Use TDD and fresh independent review for the vertical slices below. The user authorized execution and subagent review; no additional design approval is required.

**Goal:** Deliver an image generation workspace that does not depend on a canvas.

**Architecture:** Owner-scoped internal storage project preserves the existing authorization and execution kernel. Persistent Studio batches accept frozen inputs atomically; explicit execution surface separates archival from canvas projection.

**Tech Stack:** Spring Boot, PostgreSQL, jOOQ/Flyway, OpenAPI, React, TanStack Query, Zustand, shadcn.

**Spec:** [image-studio-design.md](image-studio-design.md)

## Global Constraints

- No canvas item identity, hidden nodes, active user canvas, paid provider call or real credential in the Studio path.
- Production SQL uses jOOQ; schema and generated code stay synchronized. Never edit generated code manually.
- Ordinary quantity is 1/2/4; preserve provider native multi-output semantics.
- Freeze explicit aspect ratio and reference identity/ownership; private archives only.
- UNKNOWN never auto-resubmits; archival retry never calls generation again.
- Keep old canvas task contracts and GIF behavior.

## Review Focus

- Concurrent first entry/submission must create one internal scope and one whole idempotent batch.
- Native multiple outputs must all archive with no CanvasItem insertion or duplicated provider submission.
- Unavailable/deleted capabilities and stale history parameters must preserve user draft and show actionable failure.
- Forged reference assets and batch IDs must fail owner/scope checks, including after refresh.
- Network uncertainty must retain the idempotency command; polling cannot become a POST.

### Task 1: Scope and archive separation

**Files:** project application/repository/controller; TaskService; V65 migration; focused PostgreSQL tests.

**Interfaces:** `ProjectService.ensureImageStudioScope(UUID ownerId): Project`; `requireCanvasProject(ownerId,projectId)` for public project management. Task input `executionSurface=IMAGE_STUDIO` routes result archival without node projection.

- [ ] Write PostgreSQL tests for concurrent scope creation, hidden list, owner checks and Studio native multi-output with zero nodes; run and observe missing behavior.
- [ ] Add purpose/partial unique constraint and jOOQ repository logic. Separate Studio result archival from canvas placement, rejecting contradictory node identity.
- [ ] Run focused scope/archive tests and existing canvas multi-output regression; commit verified slice.

### Task 2: Persistent Studio API and execution

**Files:** new image-studio application/domain/infrastructure/api; V66 batch migration; shared image preparation extraction; focused tests; contracts/openapi.yaml.

**Interfaces:** `/api/v1/image-studio` catalog/context; `/batches` POST/list; `/batches/{id}` detail; `/references` multipart upload. Request has prompt, capabilityId, imageParameters and frozen reference identities, never nodes. Responses contain complete batch request/tasks/results with private asset identities.

- [ ] Write and run failing acceptance tests: full batch, idempotency conflict/race, reference authorization, capability validation/defaults, partial failure/history, provider native n.
- [ ] Implement independent request preparation, frozen binding/price/reference input and atomic batch/task/usage persistence using existing Worker.
- [ ] Test submit→archive→private download, UNKNOWN/restart and archival retry with isolated fake provider; ensure zero CanvasItem writes. Run canvas generation regressions and commit.

### Task 3: Workspace UI

**Files:** frontend image-studio feature and API, App route, PageShell navigation, four locale catalogs, component tests and e2e browser script.

**Interfaces:** consumes Task 2 Studio DTOs/generated TS; existing CSRF multipart and private Asset helpers. TanStack Query owns history/status; Zustand owns draft and unresolved command.

- [ ] Write and run failing component tests for route/nav, capability parameters, submit/upload, full outputs, error/UNKNOWN, history restoration and retry identity.
- [ ] Implement responsive two-column workflow with existing shadcn controls, private previews/downloads, status polling and bounded history.
- [ ] Run feature tests, typecheck, lint and build. Run isolated browser flow at desktop/mobile sizes; preserve screenshot evidence outside Git; commit.

### Task 4: Integration, review and delivery

**Files:** generated schema/jOOQ, focused contract/migration tests, current spec/checklist/ADR, delivery evidence outside Git.

- [ ] Generate jOOQ against fresh PostgreSQL and TS from OpenAPI; test migration upgrade from V64.
- [ ] Run targeted PostgreSQL execution/recovery/owner/idempotency tests and frontend checks with fresh evidence.
- [ ] Dispatch independent whole-branch review, address material findings with regression tests, rerun relevant checks.
- [ ] Scan changed files for sensitive data, verify grayrepo-byte author/committer and actual push identity; push feature branch and open draft PR to develop if identity is verified.
- [ ] Return precise pass/fail counts, commits/PR, synthetic screenshot artifact and remaining unverified scope; do not merge/release/deploy.
