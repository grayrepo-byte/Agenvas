# 媒体能力目录与统一执行内核 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让管理员在界面配置多连接、多图片/视频能力，并让 Agent 与用户逐步骤选择，现有 Mock/ComfyUI 经同一能力执行入口运行。

**Architecture:** PostgreSQL 中保存连接、不可变版本、能力及默认值；内置 Java 适配器声明端口和执行协议。计划每一步冻结能力与连接版本，统一调度只认领绑定能力的任务，并沿用现有 Task/attempt、租约、UNKNOWN 和归档状态机。

**Tech Stack:** Java 21、Spring Boot 4、PostgreSQL 17/Flyway、React 19/TypeScript、OpenAPI、JUnit/Testcontainers、Vitest。

**Spec:** `docs/superpowers/specs/2026-09-25-media-capability-foundation-design.md`；先执行 `2026-09-25-integer-video-seconds.md`。

## Global Constraints

- 一个连接可同时承载图片和视频及多条能力；只执行随应用发布的 `MOCK_IMAGE`、`MOCK_VIDEO`、`COMFY_IMAGE_V1`、`COMFY_VIDEO_V1` 固定适配器。
- 管理员不能提交代码、任意 HTTP 地址、任意 ComfyUI 工作流或 Custom Node；普通 Agent 工具不能执行 HTTP/Shell/SQL。
- Key 只在服务端加密存储；任何读取、SSE、模型上下文、日志或导出不得出现明文、密文、nonce。
- 计划、Task 与 attempt 固定两级版本；旧 ComfyUI origin 只按历史身份核对；不确定提交为 UNKNOWN，不自动重提。
- 用户逐项确认；改选或补输入生成新修订和哈希，旧确认清空；批准事务再次检查版本、输入、额度与完整确认集合。
- Flyway 只追加，OpenAPI 为权威，生成 TS 不手改；Mock 可离线启动；无真实 ComfyUI 测试时不得宣称真实生成已验证。

## Review Focus

1. 管理员并发修改同一连接或默认值：过期 `expectedVersion` 返回 409；Task 1 测试。
2. 非管理员读写连接或跨项目提交素材 ID：拒绝且响应不含凭证；Task 2、3 测试。
3. 三步骤只确认两步、旧哈希或改选后沿用旧确认：审批拒绝且零 Task/预留；Task 3 测试。
4. ComfyUI 请求已受理但进程失联：历史 origin/请求身份核对或 UNKNOWN，绝不第二次提交；Task 4、5 测试。
5. 一个连接同时图片和视频、两个连接的三条能力：逐步骤异构选择正确路由，停用后未提交任务 BLOCKED；Task 1、4、5 测试。

---

## File Structure

| 文件 | 职责 |
| --- | --- |
| `backend/src/main/resources/db/migration/V37__media_capabilities.sql` | 连接、版本、能力、默认值、固定步骤/Task/attempt 身份列和外键 |
| `backend/src/main/java/dev/agenvas/provider/domain/{MediaCapabilityBinding,MediaAdapter,MediaAdapterRegistry,PortInput,AttemptContext,Submission,MediaPayload}.java` | 纯领域身份、端口声明、窄适配器接口和已安装适配器注册 |
| `backend/src/main/java/dev/agenvas/provider/application/{MediaCapabilityService,MediaExecutionWorker,LegacyMediaImportService}.java` | 管理员配置、统一任务分发、一次性升级 |
| `backend/src/main/java/dev/agenvas/provider/infrastructure/JdbcMediaCapabilityRepository.java` | 版本化 PostgreSQL 存取、CAS、默认值 |
| `backend/src/main/java/dev/agenvas/provider/api/MediaCapabilityController.java` | 管理员连接/能力/默认值 API，仅脱敏响应 |
| `backend/src/main/java/dev/agenvas/plan/application/{PlanDraftValidator,ExecutionPlanService}.java` | 每步骤绑定、修订、完整确认与原子审批 |
| `backend/src/main/java/dev/agenvas/task/application/{TaskService,TaskWorker}.java` | 冻结绑定、租约/epoch 与执行入口 |
| `frontend/src/features/settings/MediaSettingsPage.tsx`、`frontend/src/features/canvas/PlanApprovalPanel.tsx` | 配置和逐项选择/确认 |
| `contracts/openapi.yaml`、`frontend/src/shared/api/{client,schema}.ts` | API 合约与生成类型 |

### Task 1: 能力目录、版本和默认值

**Files:** Create `V37__media_capabilities.sql`, `MediaCapabilityBinding.java`, `MediaAdapter.java`, `MediaAdapterRegistry.java`, `PortInput.java`, `AttemptContext.java`, `Submission.java`, `MediaPayload.java`, `MediaCapabilityService.java`, `JdbcMediaCapabilityRepository.java`; test `backend/src/test/java/dev/agenvas/provider/MediaCapabilityPostgresIT.java`.

**Interfaces:** `MediaCapabilityBinding(UUID connectionId,int connectionVersion,UUID capabilityId,int capabilityVersion,String adapterId,String mappingSha256)`；`MediaCapabilityService.resolve(UUID capabilityId, Task.Kind kind, int durationSeconds)` 返回绑定；`defaultFor(Task.Kind)` 返回已发布能力。适配器只声明固定端口并执行外部协议，不得写业务表。具体形状：

```java
record PortInput(Task.Kind kind, int durationSeconds, JsonNode input) {}
record AttemptContext(Task lease, MediaCapabilityBinding binding, UUID ownerId,
        String requestKey, String originalRequestId) {}
record MediaPayload(InputStream stream, String declaredContentType) implements AutoCloseable {
    public void close() throws IOException { stream.close(); }
}
sealed interface Submission {
    record Accepted(String requestId) implements Submission {}
    record Completed(MediaPayload payload) implements Submission {}
    record Rejected(String code) implements Submission {}
    record Unknown(String code) implements Submission {}
}
interface MediaAdapter {
    String adapterId();
    boolean supports(PortInput input);
    Submission submit(AttemptContext context);
    Submission reconcile(AttemptContext context);
}
```

- [ ] **Step 1: 写失败的 PostgreSQL 测试。** Mock 新库有图片、视频两条默认能力；同一 ComfyUI 连接可以发布图片和视频；新增第二连接后可分别设默认；过期 `expectedVersion` 409；停用不删除历史版本；未安装 adapterId 不可发布。

```java
assertThat(catalog.defaultFor(Task.Kind.IMAGE_GENERATION).adapterId())
    .isEqualTo("MOCK_IMAGE");
assertThat(catalog.resolve(comfyVideoId, Task.Kind.VIDEO_GENERATION, 5)
    .connectionId()).isEqualTo(comfyConnectionId);
assertThatThrownBy(() -> catalog.setDefault(oldVersion, comfyVideoId))
    .isInstanceOf(ApiProblemException.class);
```

- [ ] **Step 2: 运行失败测试。** `cd backend && ./mvnw -Dtest=MediaCapabilityPostgresIT test`；预期表或服务不存在。
- [ ] **Step 3: 实现目录。** V37 增四类表及唯一/FK/CHECK，`plan_step`、`task`、`provider_attempt` 增可空历史绑定列，提交新任务时必填由服务约束；版本 INSERT-only、管理行 CAS；默认值按 `IMAGE_GENERATION`/`VIDEO_GENERATION` 唯一；新库 bootstrap Mock 两能力。`MediaAdapterRegistry` 以白名单固定 Bean 注入，未知 ID 报 `PROVIDER_UNSUPPORTED_CAPABILITY`。

```sql
CREATE TABLE media_provider_connection (id uuid PRIMARY KEY, name varchar(160) NOT NULL,
  enabled boolean NOT NULL, version bigint NOT NULL CHECK (version >= 0));
CREATE TABLE media_provider_connection_version (connection_id uuid NOT NULL REFERENCES media_provider_connection(id),
  version integer NOT NULL CHECK (version > 0), origin varchar(500), origin_sha256 char(64),
  credential_ciphertext bytea, credential_nonce bytea, credential_key_version integer,
  PRIMARY KEY (connection_id,version));
CREATE TABLE media_capability (id uuid PRIMARY KEY, connection_id uuid NOT NULL REFERENCES media_provider_connection(id),
  name varchar(160) NOT NULL, enabled boolean NOT NULL, version bigint NOT NULL,
  current_version integer NOT NULL);
CREATE TABLE media_capability_version (capability_id uuid NOT NULL REFERENCES media_capability(id),
  version integer NOT NULL, adapter_id varchar(80) NOT NULL, mapping_sha256 char(64) NOT NULL,
  spec_json jsonb NOT NULL, PRIMARY KEY (capability_id,version));
CREATE TABLE media_default (kind varchar(40) PRIMARY KEY, capability_id uuid NOT NULL REFERENCES media_capability(id),
  version bigint NOT NULL);
ALTER TABLE plan_step ADD COLUMN capability_id uuid,
  ADD COLUMN capability_version integer, ADD COLUMN connection_id uuid,
  ADD COLUMN connection_version integer, ADD COLUMN mapping_sha256 char(64);
ALTER TABLE task ADD COLUMN capability_id uuid, ADD COLUMN capability_version integer,
  ADD COLUMN connection_id uuid, ADD COLUMN connection_version integer;
ALTER TABLE provider_attempt ADD COLUMN capability_id uuid,
  ADD COLUMN capability_version integer, ADD COLUMN connection_id uuid,
  ADD COLUMN connection_version integer;
```

- [ ] **Step 4: 重跑测试。** `cd backend && ./mvnw -Dtest=MediaCapabilityPostgresIT test`；预期通过。
- [ ] **Step 5: 提交。** `git add backend/src && git commit -m "feat: persist versioned media capabilities"`。

### Task 2: 管理员配置 API 和界面

**Files:** Create `MediaCapabilityController.java`, `frontend/src/features/settings/MediaSettingsPage.tsx`; modify `LlmSettingsPage.tsx` or settings router, `frontend/src/shared/api/client.ts`, `contracts/openapi.yaml`; tests `MediaCapabilitySettingsPostgresIT.java`, `frontend/src/features/settings/MediaSettingsPage.test.tsx`.

**Interfaces:** `GET/POST /api/v1/settings/media-connections`；`PUT /{id}` 与 `PUT /{id}/capabilities/{capabilityId}` 带 `expectedVersion`；`PUT /api/v1/settings/media-defaults/{kind}` 带 `expectedVersion`；创建命令用 `Idempotency-Key`，同键异参 409。所有读取仅返回 `keyMask` 和配置/测试状态。

- [ ] **Step 1: 写失败测试。** 非管理员 GET/PUT 403；管理员保存连接后 GET 无 `apiKey/ciphertext/nonce`，重复幂等创建复用原 ID，同 key 异参 409；界面显示空态、保存中、冲突和“已配置、未实测”，Key 输入不进入 localStorage。

```java
mockMvc.perform(get("/api/v1/settings/media-connections").with(nonAdmin()))
    .andExpect(status().isForbidden());
assertThat(adminResponse).doesNotContain("credentialCiphertext", "credentialNonce", "apiKey");
```

```tsx
expect(screen.getByText("已配置、未实测")).toBeVisible();
expect(window.localStorage.getItem("mediaApiKey")).toBeNull();
```

- [ ] **Step 2: 运行失败测试。** `cd backend && ./mvnw -Dtest=MediaCapabilitySettingsPostgresIT test`；`cd frontend && corepack pnpm test -- MediaSettingsPage.test.tsx`。
- [ ] **Step 3: 实现 API 与设置页。** 在 `CredentialCipher` 增加媒体连接专用 AAD 方法，现有 LLM AAD 与旧密文解密行为保持不变；Mock 无 Key；ComfyUI origin 按精确地址白名单验证；DTO 仅序列化脱敏视图。设置页用 Query 读、mutation 写，冲突后保留草稿并刷新版本；连通检查只标记连通，不标记真实生成。OpenAPI 先更新，再生成 TS。

```java
return ResponseEntity.ok().cacheControl(CacheControl.noStore())
    .body(service.publicConnections());
```

```tsx
const save = useMutation({ mutationFn: updateMediaConnection,
  onError: (error) => setSaveError(error.message) });
```

- [ ] **Step 4: 验证。** `cd frontend && corepack pnpm api:generate && corepack pnpm typecheck && corepack pnpm test -- MediaSettingsPage.test.tsx`；`cd backend && ./mvnw -Dtest=MediaCapabilitySettingsPostgresIT test`。
- [ ] **Step 5: 提交。** `git add backend/src frontend/src contracts/openapi.yaml && git commit -m "feat: configure media connections in admin UI"`。

### Task 3: 每步骤绑定、改选、补输入和批准

**Files:** Modify `ExecutionPlan.java`, `ExecutionPlanService.java`, `PlanDraftValidator.java`, `JdbcExecutionPlanRepository.java`, `ExecutionPlanController.java`, `TaskService.java`, `ToolRegistry.java`, `PlanApprovalPanel.tsx`, `frontend/src/shared/api/client.ts`, `contracts/openapi.yaml`; tests `ExecutionPlanPostgresIT.java`, `PlanApprovalVersionPostgresIT.java`, `PlanApprovalPanel.test.tsx`.

**Interfaces:** 步骤加 `MediaCapabilityBinding binding`；`ExecutionPlanService.reviseStep(ownerId,projectId,planId,stepKey,capabilityId,inputPatch,expectedPlanHash)` 新增修订并令旧 PENDING/NEEDS_INPUT 变 STALE；`approve(...,planHash,List<String> confirmedStepKeys)` 检查完整且无重复确认。候选 API 只列与阶段、用途、输入和时长兼容的已发布能力。

- [ ] **Step 1: 写失败测试。** 模型仅提 `capabilityId`，伪造 endpoint/版本拒绝；缺关键帧进入 NEEDS_INPUT 且不可批准；改选生成新 ID/修订/hash 并清空确认；确认集合少一步、重复一步、旧哈希均 409 且零 Task/预留；多用户并发审批只生成一次。

```java
assertThat(revised.planHash()).isNotEqualTo(original.planHash());
assertThatThrownBy(() -> plans.approve(owner, project, revised.id(),
    revised.planHash(), List.of("shot-1"))).isInstanceOf(ApiProblemException.class);
assertThat(tasks.countByPlan(project, revised.id())).isZero();
```

```tsx
expect(screen.getByRole("button", {name: /确认执行/})).toBeDisabled();
await user.click(screen.getByLabelText(/确认镜头 shot-2/));
expect(screen.getByRole("button", {name: /确认执行/})).toBeEnabled();
```

- [ ] **Step 2: 运行失败测试。** `cd backend && ./mvnw -Dtest=ExecutionPlanPostgresIT,PlanApprovalVersionPostgresIT test`；`cd frontend && corepack pnpm test -- PlanApprovalPanel.test.tsx`。
- [ ] **Step 3: 实现步骤修订与审批。** 新步骤入库与哈希固定连接/能力 ID、版本、映射摘要、输入版本；默认值仅提案时读取。服务端补输入重新验证项目归属、MIME、端口和关键帧选择；批准事务验证确认集合与当前步骤键精确相等、版本未漂移、额度和输入仍有效，然后一次创建 Task/预留/事件。前端每次改选后清空勾选，并展示费用来源及真实测试状态。

```java
Set<String> expected = plan.steps().stream().map(ExecutionPlan.Step::stepKey)
    .collect(Collectors.toSet());
if (confirmedStepKeys.size() != expected.size()
        || !expected.equals(Set.copyOf(confirmedStepKeys)))
    throw conflict("请逐项确认所有步骤");
```

- [ ] **Step 4: 验证。** `cd frontend && corepack pnpm api:generate && corepack pnpm typecheck && corepack pnpm test -- PlanApprovalPanel.test.tsx`；`cd backend && ./mvnw -Dtest=ExecutionPlanPostgresIT,PlanApprovalVersionPostgresIT test`。
- [ ] **Step 5: 提交。** `git add backend/src frontend/src contracts/openapi.yaml && git commit -m "feat: bind and confirm media capability per plan step"`。

### Task 4: 固定 Mock/ComfyUI 适配器与统一调度

**Files:** Create `MediaExecutionWorker.java`, `MockImageAdapter.java`, `MockVideoAdapter.java`, `ComfyUiImageAdapter.java`, `ComfyUiVideoAdapter.java`; modify existing provider workers/schedulers, `TaskService.java`, `TaskWorker.java`, `ComfyUiClientRegistry.java`; tests `MockImageSchedulerPostgresIT.java`, `ComfyUiImagePostgresIT.java`, `ComfyUiVideoPostgresIT.java`, `TaskLeasePostgresIT.java`.

**Interfaces:** `MediaExecutionWorker.submitOnce(workerId)` 只认领带绑定的 READY 媒体任务；`pollOnce(workerId)` 只处理原请求 ID；适配器返回 `Submission.Accepted/Completed/Rejected/Unknown`，不直接改 Task 表；`TaskService.checkpointBeforeSubmit(Task,MediaCapabilityBinding)` 返回 `AttemptContext`，`recordSubmission(Task,Submission)` 用预期状态与 epoch CAS。

- [ ] **Step 1: 写失败测试。** 同项目一个 ComfyUI 连接图片和视频两能力均按固定模板提交；另一个步骤选择 Mock；停用后未提交 Task BLOCKED；提交接受后断线只按原 ID 查询；旧 epoch 回写失败、取消晚到不启下游。

```java
assertThat(comfyFake.imageSubmissions()).isEqualTo(1);
assertThat(comfyFake.videoSubmissions()).isEqualTo(1);
assertThat(mockFixtureCount).isEqualTo(1);
assertThat(taskAfterDisable.status()).isEqualTo(Task.Status.BLOCKED);
```

- [ ] **Step 2: 运行失败测试。** `cd backend && ./mvnw -Dtest=MockImageSchedulerPostgresIT,ComfyUiImagePostgresIT,ComfyUiVideoPostgresIT,TaskLeasePostgresIT test`。
- [ ] **Step 3: 实现内核。** 先以绑定 ID/版本决定固定适配器，再使用现有短事务认领、attempt、epoch、UNKNOWN 和 Asset 归档函数；网络在事务外。移除基于全局 `agenvas.provider.mode` 决定业务路由的调度条件；保留旧 Worker 的只读历史恢复路径，直到 Task 5 映射历史身份。归档失败只重读原结果，不提交新生成请求。

```java
MediaAdapter adapter = registry.require(binding.adapterId());
AttemptContext attempt = tasks.checkpointBeforeSubmit(lease, binding);
Submission outcome = adapter.submit(attempt);
tasks.recordSubmission(lease, outcome); // 内部使用预期状态和 leaseEpoch CAS
```

- [ ] **Step 4: 验证。** `cd backend && ./mvnw -Dtest=MockImageSchedulerPostgresIT,ComfyUiImagePostgresIT,ComfyUiVideoPostgresIT,TaskLeasePostgresIT,ComfyUiReconciliationPostgresIT test`。
- [ ] **Step 5: 提交。** `git add backend/src && git commit -m "feat: dispatch media tasks through fixed adapters"`。

### Task 5: 旧配置/请求升级与交付门禁

**Files:** Create `LegacyMediaImportService.java` and migration `V38__media_legacy_import_marker.sql`; modify `ComfyUiUnknownTaskReconciler.java`, `TaskRecoveryScheduler.java`, `README.md`, `docs/operations/backup-restore.md`, `docs/MVP-SPEC.md`, `docs/DEVELOPMENT-CHECKLIST.md`; tests `MediaLegacyImportPostgresIT.java`, `ComfyUiAcceptedCrashPostgresIT.java`, `ProjectEventStreamPostgresIT.java`.

**Interfaces:** `LegacyMediaImportService.importBeforeWorkers()` 在调度启动前执行、幂等；无法唯一映射的历史行标 `LEGACY_UNRESOLVED`，已提交按原 origin 和请求 ID 核对，未提交 BLOCKED；之后数据库为配置真相。

- [ ] **Step 1: 写失败测试。** 从旧 Mock/ComfyUI 配置导入后同一 ComfyUI 地址只有一个连接与两能力，重复启动不复制；V34 origin 精确映射到历史版本；缺失/歧义保留 UNKNOWN/BLOCKED；改变环境变量不改数据库默认值；SSE 重连仍可补发计划修订事件。

```java
importer.importBeforeWorkers();
importer.importBeforeWorkers();
assertThat(repository.countConnectionsForOrigin(originSha256)).isEqualTo(1);
assertThat(oldAcceptedTask.status()).isEqualTo(Task.Status.UNKNOWN);
assertThat(fakeComfy.submissionCount()).isZero();
```

- [ ] **Step 2: 运行失败测试。** `cd backend && ./mvnw -Dtest=MediaLegacyImportPostgresIT,ComfyUiAcceptedCrashPostgresIT,ProjectEventStreamPostgresIT test`。
- [ ] **Step 3: 实现升级。** 在数据库标记下导入旧启动配置；匹配旧 Provider/workflow/origin 指纹与 attempt，无法唯一匹配不猜测；历史连接版本保留 origin、工作流及密钥版本。调度只有导入完成后启动。删除 README 中“媒体切换只靠 env”说明，写备份需保存 DB、Asset 卷和密钥环；清单按实际测试状态更新。

```java
if (matches.size() != 1) {
    repository.markLegacyUnresolved(task.id());
    return; // 不创建新的 Provider 提交
}
repository.bindHistoricalAttempt(task.id(), matches.getFirst());
```

- [ ] **Step 4: 完整验证。** `cd backend && ./mvnw verify`；`cd frontend && corepack pnpm typecheck && corepack pnpm lint && corepack pnpm test && corepack pnpm build`；记录实际 PostgreSQL、假服务及 UI 结果，真实 ComfyUI 未运行单列。
- [ ] **Step 5: 提交。** `git add backend/src frontend/src contracts/openapi.yaml README.md docs && git commit -m "feat: migrate legacy media configuration and recovery"`。

## Self-review / handoff gate

Task 1–5 覆盖目录、管理端、计划审批、执行与历史升级；五条 Review Focus 均有对应失败测试。每个执行任务先读规格和当前仓库，再据实调整文件定位与测试夹具；不得放宽审批、租约、凭证或旧请求恢复规则。完成此计划后再执行固定云渠道计划。
