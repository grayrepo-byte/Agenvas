# 整数秒创作与导出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新镜头、计划、任务、用量和顺序导出只接受整数秒，同时保留历史版本与素材探测的毫秒精度。

**Architecture:** 先增加不可变历史的升级迁移和任务输入 Schema 分流，再同步领域校验、API、Agent 工具和 UI。已受理旧任务按原冻结输入恢复；新的业务边界统一使用 `durationSeconds`、`startSeconds`、`endSeconds`。

**Tech Stack:** Java 21、Spring Boot 4、PostgreSQL 17/Flyway、React/TypeScript、OpenAPI、JUnit/Testcontainers、Vitest。

**Spec:** `docs/superpowers/specs/2026-09-25-media-capability-foundation-design.md` §4–6；`docs/adr/0003-integer-business-video-seconds.md`。

## Global Constraints

- 镜头整数范围为 1–30 秒；现有 ComfyUI 固定视频模板为 1–5 秒；Mock 为 1–30 秒。
- 导出用户起止点与提案总时长用整数秒，总时长上限 60 秒；`asset.duration_ms`、ffprobe、FFmpeg 内部仍保留毫秒。
- Flyway 迁移只增不改；`artifact_version` 永不 UPDATE/DELETE；旧用量账本永不改写。
- 已受理的旧任务只查询原请求或归档；不因单位转换重新提交。旧待审批计划与导出提案失效。
- `contracts/openapi.yaml` 为权威；生成 `frontend/src/shared/api/schema.ts`，不得手改生成文件。
- 此计划不调用真实媒体 Provider；行为测试使用 PostgreSQL、Mock 与本地假 ComfyUI。

## Review Focus

1. 当前镜头是 1250ms：升级后仍显示 1.25 秒，阻止新计划，用户明确改成整数秒才创建新版本；Task 1 测试。
2. 当前镜头恰为 5000ms：升级创建新版本、保留旧版本和引用，原关键帧选择不能静默绑定新镜头；Task 1 测试。
3. 已受理旧视频/导出 Task：旧 `schemaVersion=1` 输入仍按毫秒恢复，新 `schemaVersion=2` 才按秒执行；Task 3 测试。
4. 真实素材为 1.25 秒：用户不能选择 2 秒导出终点，素材 `durationMs=1250` 不被取整；Task 4 测试。
5. 旧小数用量：历史统计如实显示 `1.250`，新预留写整数 `5`；Task 3、4 测试。

---

## File Structure

| 文件 | 职责 |
| --- | --- |
| `backend/src/main/resources/db/migration/V36__integer_business_video_seconds.sql` | 追加迁移标记、旧待审状态与升级辅助约束；不更新不可变版本 |
| `backend/src/main/java/dev/agenvas/artifact/application/ShotDurationUpgradeService.java` | 幂等升级当前整秒镜头到新版本，保留小数旧版本并暴露需用户修订状态 |
| `backend/src/main/java/dev/agenvas/artifact/application/ArtifactContentValidator.java`、`ShotRedoService.java` | 新镜头内容与局部修改整数秒校验 |
| `backend/src/main/java/dev/agenvas/llm/application/{CreativeArtifactToolService,ToolRegistry}.java`、`backend/src/main/java/dev/agenvas/llm/infrastructure/MockStoryboardChatGateway.java` | Agent 输入 Schema 与 Mock 提案 |
| `backend/src/main/java/dev/agenvas/plan/application/{PlanDraftValidator,PlanWorkflowPolicy}.java` | 时长固定和能力限制 |
| `backend/src/main/java/dev/agenvas/provider/{application/{MockVideoWorker,ComfyUiVideoWorker},infrastructure/ComfyUiVideoWorkflow}.java` | 新秒数到固定协议的换算和旧任务分流 |
| `backend/src/main/java/dev/agenvas/export/application/{MediaExportService,ExportProposalService,MediaExportWorker}.java` | 整数秒提案、预览与 FFmpeg 边界；旧任务仍用毫秒 |
| `backend/src/main/java/dev/agenvas/usage/application/UsageService.java` | 新整数用量；历史账本只读 |
| `contracts/openapi.yaml`、Java API DTO、前端画布表单与对应测试 | 同步契约、输入和显示 |

### Task 1: 历史镜头与待审对象迁移

**Files:** Create `backend/src/main/resources/db/migration/V36__integer_business_video_seconds.sql`, `backend/src/main/java/dev/agenvas/artifact/application/ShotDurationUpgradeService.java`; modify `ExportProposal.java` and repository；test `backend/src/test/java/dev/agenvas/artifact/ShotDurationUpgradePostgresIT.java`.

**Interfaces:** `ShotDurationUpgradeService.upgradeCurrentWholeSecondShots()` 在媒体 Worker 启动前运行一次且可重启；`ShotDurationUpgradeService.requiresUserRevision(JsonNode)` 判别旧小数内容。`ExportProposal.Status.STALE` 为待审提案失效状态。

- [ ] **Step 1: 写失败的 PostgreSQL 测试。** 构造 5000ms 和 1250ms 两个当前 SHOT、一个指向 5000ms 旧版本的关键帧选择、PENDING 计划及导出提案；调用升级两次；断言整秒镜头增加一个 `schema_version=2` 的 `durationSeconds=5` 新版本、旧版本及引用保留，小数镜头未变，选择仍指旧版本，两个待审项变 STALE，已受理 Task/历史用量未变。

```java
assertThat(upgraded.content().path("durationSeconds").intValue()).isEqualTo(5);
assertThat(old.content().path("durationMs").intValue()).isEqualTo(5000);
assertThat(selection.shotVersionId()).isEqualTo(old.id());
assertThat(service.requiresUserRevision(fractional.content())).isTrue();
```

- [ ] **Step 2: 运行失败用例。** `cd backend && ./mvnw -Dtest=ShotDurationUpgradePostgresIT test`；预期因升级服务或新状态缺失失败。
- [ ] **Step 3: 实现迁移。** V36 给导出状态约束追加 `STALE` 并加唯一升级标记表；服务按项目/Artifact 锁顺序读取当前镜头，只有 `durationMs % 1000 == 0` 时 INSERT 新版本并复制旧引用行，再 CAS 更新 `artifact.current_version_id` 与 `artifact.version`，不修改旧行；小数镜头保留供 UI 明确编辑。旧 PENDING 计划与导出提案设 STALE，不能触发任务。批次升级使用数据库锁及唯一标记，重启不会重复插入。

```sql
ALTER TABLE export_proposal DROP CONSTRAINT ck_export_proposal_status;
ALTER TABLE export_proposal DROP CONSTRAINT ck_export_proposal_decision;
ALTER TABLE export_proposal ADD CONSTRAINT ck_export_proposal_status
  CHECK (status IN ('PENDING','APPROVED','REJECTED','STALE'));
ALTER TABLE export_proposal ADD CONSTRAINT ck_export_proposal_decision CHECK (
  (status IN ('PENDING','STALE') AND approved_task_id IS NULL
   AND decided_by_user_id IS NULL AND decided_at IS NULL)
  OR (status = 'APPROVED' AND approved_task_id IS NOT NULL
   AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL)
  OR (status = 'REJECTED' AND approved_task_id IS NULL
   AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL));
CREATE TABLE shot_duration_upgrade (artifact_id uuid PRIMARY KEY REFERENCES artifact(id),
  old_version_id uuid NOT NULL, new_version_id uuid, upgraded_at timestamptz NOT NULL);
```

- [ ] **Step 4: 重跑测试。** `cd backend && ./mvnw -Dtest=ShotDurationUpgradePostgresIT test`；预期通过，检查没有旧版 UPDATE、重复新版本或外部提交。
- [ ] **Step 5: 提交。** `git add backend/src/main/resources/db/migration/V36__integer_business_video_seconds.sql backend/src/main/java/dev/agenvas/artifact backend/src/main/java/dev/agenvas/export backend/src/test/java/dev/agenvas/artifact/ShotDurationUpgradePostgresIT.java && git commit -m "feat: migrate whole-second shot revisions safely"`。

### Task 2: 新镜头和计划只接收整数秒

**Files:** Modify `ArtifactContentValidator.java`, `ShotRedoService.java`, `ShotRedoController.java`, `CreativeArtifactToolService.java`, `ToolRegistry.java`, `MockStoryboardChatGateway.java`, `PlanDraftValidator.java`, `PlanWorkflowPolicy.java`, `ComfyUiVideoWorkflow.java`, `contracts/openapi.yaml`; tests `ArtifactContentValidatorTest.java`, `ExecutionPlanPostgresIT.java`, `ComfyUiVideoWorkflowTest.java`.

**Interfaces:** 新内容 `schemaVersion=2` 用 `durationSeconds:int`；新 Task 输入 `schemaVersion=2` 同名；`ComfyUiVideoWorkflow.supportsDurationSeconds(int)` 只接受 1–5；旧 v1 内容只读，不经新建/编辑校验器。

- [ ] **Step 1: 写失败测试。** 用新镜头 `durationSeconds=5` 提案通过，`durationSeconds=0/31/1.25` 与新请求带 `durationMs` 均失败；旧 1250ms 镜头无法生成计划；ComfyUI 只接受整数 1–5。

```java
assertThat(workflow.supportsDurationSeconds(5)).isTrue();
assertThat(workflow.supportsDurationSeconds(6)).isFalse();
assertThatThrownBy(() -> validator.validate(SHOT, fractionalSeconds))
    .isInstanceOf(ApiProblemException.class);
```

- [ ] **Step 2: 运行失败用例。** `cd backend && ./mvnw -Dtest=ArtifactContentValidatorTest,ExecutionPlanPostgresIT,ComfyUiVideoWorkflowTest test`；预期新字段断言失败。
- [ ] **Step 3: 改服务端输入边界。** 新镜头 Schema、局部重做 DTO、Agent JSON Schema、Mock 文本输出、计划输入与估算统一使用整数秒；旧镜头明确修订时移除旧 `durationMs`，写入新的 `durationSeconds` 内容版本，关键帧旧版本仍要求用户重选。ComfyUI 长度映射 `durationSeconds * 4 * 4 + 1`，仅在请求构造时换算帧数；不对旧小数取整。

```java
requireInteger(content, "durationSeconds", 1, 30);
int durationSeconds = shot.content().path("durationSeconds").intValue();
if (!shot.content().has("durationSeconds")) throw invalid("请先将镜头时长改为整数秒");
taskInput.put("schemaVersion", 2).put("durationSeconds", durationSeconds);
```

- [ ] **Step 4: 重跑上述测试。** 预期通过；生成 TS：`cd frontend && corepack pnpm api:generate && corepack pnpm typecheck`。
- [ ] **Step 5: 提交。** `git add backend/src contracts/openapi.yaml frontend/src/shared/api/schema.ts && git commit -m "feat: require integer seconds for new shots and plans"`。

### Task 3: Mock、ComfyUI 和用量分流

**Files:** Modify `MockVideoWorker.java`, `ComfyUiVideoWorker.java`, `ComfyUiVideoWorkflow.java`, `UsageService.java`; tests `MockImageSchedulerPostgresIT.java`, `ComfyUiVideoPostgresIT.java`, `TaskRecoveryPostgresIT.java`.

**Interfaces:** Worker 解读 `schemaVersion=1` 的冻结 `durationMs` 或 v2 的 `durationSeconds`；新预留 `videoSeconds` 保持 API 十进制字符串但值为整数文本，历史读值不变。

- [ ] **Step 1: 写失败测试。** 新 5 秒 Mock 与 ComfyUI 请求时长准确；旧 `durationMs=1250` 的已受理 Task 仅轮询旧请求或归档，不重新提交；新用量为 `"5"`，历史 `"1.250"` 保留。

```java
assertThat(newTask.input().path("durationSeconds").intValue()).isEqualTo(5);
assertThat(newEntry.quantity().path("videoSeconds").asText()).isEqualTo("5");
assertThat(oldEntry.quantity().path("videoSeconds").asText()).isEqualTo("1.250");
assertThat(fakeComfy.submissionCount()).isEqualTo(0);
```

- [ ] **Step 2: 运行失败用例。** `cd backend && ./mvnw -Dtest=MockImageSchedulerPostgresIT,ComfyUiVideoPostgresIT,TaskRecoveryPostgresIT test`；预期新输入/用量断言失败。
- [ ] **Step 3: 实现 Schema 分流。** 提取 `VideoDuration.fromFrozenTask(JsonNode)` 返回准确 `Duration`；v2 验证 1–30 整数，v1 只供已存在任务恢复；新用量取 `durationSeconds` 写整数字符串，查询不重算旧 ledger。调整 ComfyUI 帧数公式保持 5 秒旧行为，Mock FFmpeg 参数由整数秒构造。

```java
static Duration fromFrozenTask(JsonNode input) {
    return input.path("schemaVersion").asInt(1) == 1
            ? Duration.ofMillis(input.path("durationMs").asInt())
            : Duration.ofSeconds(input.path("durationSeconds").asInt());
}
```

- [ ] **Step 4: 重跑测试。** 预期通过；另跑 `cd backend && ./mvnw -Dtest=ComfyUiAcceptedCrashPostgresIT,ComfyUiReconciliationPostgresIT test` 确认旧请求不被新提交。
- [ ] **Step 5: 提交。** `git add backend/src && git commit -m "feat: execute integer-second media tasks while preserving legacy recovery"`。

### Task 4: 顺序导出、前端和升级说明

**Files:** Modify `MediaExportService.java`, `ExportProposalService.java`, `MediaExportWorker.java`, `MediaExportController.java`, `ProjectExportManifestService.java`, `ToolRegistry.java`, `MockStoryboardChatGateway.java`, `contracts/openapi.yaml`, `frontend/src/features/canvas/{ManualStoryboardPanel,ShotRedoEditor,MediaExportPanel}.tsx` and their tests, `docs/MVP-SPEC.md`, `docs/DEVELOPMENT-CHECKLIST.md`, `docs/operations/backup-restore.md`.

**Interfaces:** 新导出片段 `startSeconds/endSeconds:int`，提案 `durationSeconds:int`；FFmpeg 使用 `Duration.ofSeconds` 转固定参数；旧 `schemaVersion=1` 已受理导出仍按 `startMs/endMs`。

- [ ] **Step 1: 写失败测试。** 1.25 秒 Asset 只允许 `[0,1]`，拒绝 `[0,2]`；2+3 秒片段总计 5；同幂等键不同区间 409；旧 v1 导出任务按旧毫秒执行。前端数字输入 `step=1` 且不提交小数，旧 1.25 秒镜头显示“需调整为整数秒”。

```java
assertThat(preview.inputSnapshot().path("durationSeconds").intValue()).isEqualTo(5);
assertThatThrownBy(() -> exports.preview(owner, project, List.of(
    new SegmentRequest(videoId, versionId, 0, 2))))
    .isInstanceOf(ApiProblemException.class);
```

```tsx
expect(screen.getByText(/需调整为整数秒/)).toBeVisible();
expect(screen.getByLabelText(/结束秒数/)).toHaveAttribute("step", "1");
```

- [ ] **Step 2: 运行失败用例。** `cd backend && ./mvnw -Dtest=MediaExportPostgresIT,ExportProposalPostgresIT test`；`cd frontend && corepack pnpm test -- ManualStoryboardPanel.test.tsx ShotRedoEditor.test.tsx MediaExportPanel.test.tsx`。
- [ ] **Step 3: 实现导出和 UI。** 新提案/导出 JSON 标 v2；范围比较用 `Math.multiplyExact(endSeconds,1000) <= asset.durationMs()`，总和限制 60；Worker v1/v2 分流；表单只写整数秒，历史显示保留原毫秒精度。OpenAPI 与 Java DTO 同步；生成 TS，不手改；文档写明旧待审重建、旧关键帧重选与备份顺序。

```java
if (!segment.has("endSeconds") || !segment.path("endSeconds").isIntegralNumber())
    throw invalid("结束时间必须为整数秒");
if (Math.multiplyExact(endSeconds, 1000) > asset.durationMs())
    throw invalid("裁剪终点超过素材实际时长");
```

- [ ] **Step 4: 验证。** `cd frontend && corepack pnpm api:generate && corepack pnpm typecheck && corepack pnpm lint && corepack pnpm test`；`cd backend && ./mvnw verify`。记录实际结果；PostgreSQL/Testcontainers 不可用时明确记“未运行”，不可写通过。
- [ ] **Step 5: 提交。** `git add backend/src frontend/src contracts/openapi.yaml docs && git commit -m "feat: use integer seconds in exports and creative UI"`。

## Self-review / handoff gate

执行前读两份规格、ADR、AGENTS.md。Task 1–4 分别覆盖迁移、计划、执行和导出/UI；五条 Review Focus 对应 Task 1、1、3、4、3/4。此计划结束后运行下一份媒体能力基础计划；不要把通用层完成描述成 GPT/Seedance 已真实生成。
