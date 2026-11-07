# 服装提取 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement task-by-task. Independent catalog backend and frontend work may use superpowers:dispatching-parallel-agents after fixing their interfaces below.

**Goal:** 图片工具栏支持固定样式的 OpenAI 服装提取，结果可选入库，与三方服装统一浏览且生命周期独立。

**Architecture:** 复用图片后处理 Task 与 OpenAI 多图编辑，固定版本的内置样式 PNG 是第二张输入。自建条目在 referencecatalog 中独立持久化和归档，统一目录合并展示，三方同步/清理无权删除自建记录或字节。

**Tech Stack:** Existing Java 21/Spring Boot/jOOQ/PostgreSQL and React/TanStack Query/shadcn. No dependency upgrades.

**Spec:** [设计](costume-extraction-design.md)

## Global Constraints

- 单管理员、实例共享、统一服装列表；只有后台来源及归档生命周期区分。
- OpenAPI 为权威；生成 TS/jOOQ 不手改；Flyway 只增不改。
- 文件/网络 I/O 不持有数据库事务；CAS、幂等、UNKNOWN 与显式重试沿用现有语义。
- 不运行全量基线或全量测试；真实 PostgreSQL 专项与 Mock/真实 Provider 验证分别记录。
- 已在外部管理的隔离 worktree，保留当前工作区；不自动提交、推送或发布。

## Review Focus

- 同步/停用/清理三方来源后，自建图片仍可预览、搜索和导入。
- 同一入库命令重放只生成一个条目；不同输入复用命令键返回冲突。
- 全局目录读取不绕过来源项目保存权限或目标项目导入权限。
- 打开提取时固定来源，来源被切换后提交 CAS 冲突并保留说明。
- 样式图只影响展示标准，任务冻结确切图片摘要，不能在恢复时读取另一个版本。

## Shared Interfaces

- Unified `ReferenceCatalogEntry` adds optional `origin: THIRD_PARTY | SELF_CREATED`, `version: integer`. Self-created entries use sourceId `self-created`, snapshotId=id UUID, id=UUID string; one variant `front`, readable preview URL under `/api/v1/reference-catalog/costumes/{id}/image`.
- `POST /api/v1/projects/{projectId}/reference-catalog/costumes`: `{canvasItemId, sourceVersionId, expectedCanvasItemVersion, name, category?, tags, commandKey}` returns unified `ReferenceCatalogEntry`.
- `PATCH /api/v1/reference-catalog/costumes/{id}`: `{expectedVersion, name, category?, tags}` returns unified entry; `DELETE` uses query expectedVersion and returns 204.
- `POST /api/v1/projects/{projectId}/reference-catalog/costumes/{id}/import`: `{commandKey, expectedVersion, placement?}` returns existing `ReferenceCatalogImport`.
- Frontend `SaveCostumeReferenceButton({projectId,canvasItemId,sourceVersionId,expectedCanvasItemVersion,sourceTitle,disabled?})`; root integrates it in MediaCanvasCard. Locale additions coordinated by root, new keys prefix `costume.`.

### Task 1: Fixed-style image extraction

**Files:** ImageOperation.java, ImageOperationSpec.java, MediaFunction.java, MediaFunctionService.java, DirectMediaTaskService.java, OpenAiImage2Adapter.java; new immutable style resource/helper; migration V57; OpenAPI image enums; frontend mediaFunctions.ts, ImageOperationPanel.tsx, MediaCanvasCard.tsx and four locale catalogs.
**Interfaces:** Produces COSTUME_EXTRACT / IMAGE_COSTUME_EXTRACT using existing run-image-operation endpoint and compatible two-image OpenAI capability.
- [x] Add tests asserting operation prompt and ratio, OpenAI-only capacity validation and exact source/style ordering. Run relevant tests and observe failures.
- [x] Implement operation, built-in prompt migration, frozen style descriptor and Provider attachment; generate and inspect self-owned style image through imagegen.
- [x] Add toolbar action, optional explanation and saving entry integration; pin source when opening.
- [x] Run targeted Java operation/function/Provider tests and frontend media function/panel/card tests, then typecheck.

### Task 2: Durable self-created catalog backend

**Files:** referencecatalog Java domain/application/infrastructure/api and tests; migration V58; root integrates generated jOOQ and OpenAPI catalog schemas.
**Interfaces:** Implements Shared Interfaces; unified list includes self-created entries when no external source filter; source update/cache operations remain limited to external tables/files.
- [x] Write failing PostgreSQL/HTTP tests for unified list, save/import replay, permissions and external-source cleanup isolation.
- [x] Implement independent archive and command snapshots with short transactions, CAS edit/delete and authenticated image access. Keep accepted bytes available for in-progress transfers.
- [x] Merge entries before common filtering/pagination; add backend origin and metadata version.
- [x] Regenerate jOOQ against isolated PostgreSQL after both migrations; run dedicated referencecatalog integration tests.

### Task 3: Unified browsing and optional save frontend

**Files:** features/reference-catalog, shared/api/referenceCatalog.ts, new SaveCostumeReferenceButton and targeted tests. Root owns MediaCanvasCard, locale JSON, generated schema and docs.
**Interfaces:** Consumes Shared Interfaces, exports save button; no separate self-created tab or source-management workflow.
- [x] Add failing tests for mixed costume cards, self-created import, optional save and CAS/error preservation.
- [x] Reuse current grid/preview/application flows, dispatch import by origin, add administrator metadata editing/deletion for self-created entries.
- [x] Keep stable save/import intentions across uncertain responses; disable mutation while busy, retain input after failure.
- [x] Run reference catalog and new button tests. Root runs typecheck/lint/build and integrates toolbar.

### Task 4: Integrated verification and documentation

**Files:** contracts/openapi.yaml, generated schema/jOOQ, docs/MVP-SPEC.md, CONTEXT.md and new ADR; DEVELOPMENT-CHECKLIST.md.
- [x] Generate TypeScript from OpenAPI and compile backend; resolve exhaustive operation registries without disabling checks.
- [x] Run targeted PostgreSQL operation/catalog tests, frontend feature tests, lint and build. Check unified list and no-source-dependency with browser fixture when available.
- [x] Fresh independent review of the complete diff; fix material findings and run relevant checks.
- [x] Record actual results and limitations, especially real OpenAI visual quality if untested; leave changes reviewable in current worktree.

## 执行记录

- 已在现有隔离工作区完成实现；按用户后续要求提交并整合本地 develop，未推送或部署。参考库后端和前端按固定接口并行完成，提取及最终集成由主执行者完成。
- 已验证单管理员、统一服装列表和后台来源隔离；三方同步/停用/清理及来源项目删除后，已发布自建素材仍可预览和导入。
- 独立审阅发现元数据与导入的陈旧版本恢复缺口，已补齐读取/刷新和回执核对；六项恢复用例先失败后通过，未遗留审阅问题。
- 工程选择：保留自建目录墓碑及受理命令所需文件，不实现自动 GC；尚未完成的保存/重放继续要求来源项目活动。此限制不影响已发布条目的独立使用。
- 实际测试和未验证范围见开发清单的本功能条目。
