# GPT Image 2 与 Seedance 固定适配器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已实现的媒体能力层上新增 GPT Image 2 图片与火山方舟中国区 Seedance 首帧图生视频的固定 Java 适配器。

**Architecture:** 两个内置适配器分别封装官方 HTTPS 协议、固定模型、参数与响应解析，公共目录提供配置/审批/Task 状态机。OpenAI 同步图片按无参考图/有参考图走生成/编辑；方舟异步任务只提交一次，保存原任务 ID 后查询与归档。

**Tech Stack:** Java 21、Spring Boot 4、PostgreSQL 17/Flyway、现有 HTTP/Asset 服务、React/TypeScript、JUnit/Testcontainers、本地假 HTTP 服务。

**Spec:** `docs/superpowers/specs/2026-09-25-fixed-media-provider-adapters-design.md`；先完成 `2026-09-25-integer-video-seconds.md` 和 `2026-09-25-media-capability-foundation.md`。协议依据 [OpenAI 图片指南](https://developers.openai.com/api/docs/guides/image-generation)、[GPT Image 2 模型](https://developers.openai.com/api/docs/models/gpt-image-2)、[方舟创建任务](https://docs.volcengine.com/docs/ark/create-video-generation-task-api?lang=zh)与[方舟查询任务](https://docs.volcengine.com/docs/ark/list-video-generation-tasks-api?lang=zh)。

## Global Constraints

- 固定适配器标识：`OPENAI_GPT_IMAGE_2`、`ARK_SEEDANCE_2_I2V`；不提供 Groovy/动态脚本/RunningHub/任意模型名输入。
- OpenAI 模型 `gpt-image-2`：无参考图用 Images Generations，有固定项目参考图用 Images Edits；质量只允许 `low/medium/high`，默认 `medium`，归档 PNG。
- Seedance 固定模型 `doubao-seedance-2-0-260128`，火山方舟中国区北京官方 HTTPS，固定单张关键帧、4–15 整数秒、无声 MP4；不支持时步骤不可选。
- Key 复用服务端加密存储，不入前端持久存储、日志、SSE、Prompt、URL、项目导出；服务端只连接固定官方 origin。
- 同步 OpenAI 结果丢失、异步 Seedance 创建响应丢失都为 UNKNOWN，除非可证明未受理；不自动重新付费提交。
- Seedance 临时视频 URL 仅按允许的 HTTPS 媒体来源下载，重定向、DNS、大小、MIME、解码和存储限额均检查；归档失败不重新生成。
- 用户已确认本阶段不做真实付费调用；本地假服务测试不能记为真实渠道已验证。

## Review Focus

1. OpenAI 参考图版本来自别的项目或是 URL：拒绝，且请求不会离开本机；Task 2 测试。
2. OpenAI 请求超时或进程在响应持久化前死亡：UNKNOWN，自动调度不发第二次请求；Task 2 测试。
3. Seedance 3/16 秒或小数秒：候选列表不提供且服务端批准拒绝，不能裁剪替代；Task 1、3 测试。
4. Seedance 创建已受理后断线：只凭保存的原任务 ID 查询；没有 ID 为 UNKNOWN，不从列表猜 ID 或重新提交；Task 3、4 测试。
5. Seedance 返回内网/重定向/过期媒体 URL 或有效文件带音轨：阻断恶意下载、过期只重查原任务，归档结果必须无声且 Asset 合法；Task 4 测试。

---

## File Structure

| 文件 | 职责 |
| --- | --- |
| `backend/src/main/java/dev/agenvas/provider/infrastructure/OpenAiImage2Client.java` | 固定 Images API 请求、响应解码和网络错误分类 |
| `backend/src/main/java/dev/agenvas/provider/application/OpenAiImage2Adapter.java` | 输入端口、项目参考图读取、提交与归档交接 |
| `backend/src/main/java/dev/agenvas/provider/infrastructure/ArkSeedanceClient.java` | 固定方舟 create/query API、任务状态和错误分类 |
| `backend/src/main/java/dev/agenvas/provider/infrastructure/ArkMediaDownloadPolicy.java` | HTTPS allowlist、DNS/重定向和有界流式下载 |
| `backend/src/main/java/dev/agenvas/provider/infrastructure/FixedCloudDns.java` | 两个固定云请求与结果下载共用的公网 DNS 校验 |
| `backend/src/main/java/dev/agenvas/provider/application/ArkSeedance2Adapter.java` | 关键帧、时长、无声 MP4 和原请求核对 |
| `backend/src/main/java/dev/agenvas/provider/application/MediaCapabilityService.java` | 注册两个固定能力与可配置参数范围 |
| `backend/src/main/java/dev/agenvas/asset/application/AssetService.java` | 已有安全归档/媒体探测入口，必要时增加无声验证 |
| `contracts/openapi.yaml`、`frontend/src/features/settings/MediaSettingsPage.tsx`、`frontend/src/features/canvas/PlanApprovalPanel.tsx` | 脱敏配置字段及真实测试状态展示 |

### Task 1: 固定能力声明、配置与 UI

**Files:** Modify `MediaAdapterRegistry.java`, `MediaCapabilityService.java`, `MediaCapabilityController.java`, `MediaSettingsPage.tsx`, `PlanApprovalPanel.tsx`, `contracts/openapi.yaml`; tests `MediaCloudCapabilityPostgresIT.java`, `MediaSettingsPage.test.tsx`, `PlanApprovalPanel.test.tsx`.

**Interfaces:** 管理端仅能选固定 `adapterId` 并提交 API Key 及适配器声明的质量/画幅；`MediaCapabilityService.candidates(kind,input)` 以端口和时长过滤；配置状态为 `UNTESTED` 直到真实生成验收（本轮不改变）。

- [ ] **Step 1: 写失败测试。** 保存 OpenAI/方舟配置后读取只见 Key 掩码；模型 ID/自定义 API 路径/不支持质量被拒；Seedance 候选只出现在 4–15 秒且已有已选关键帧的 VIDEO 步骤；审批页列清“已配置、未实测”。

```java
assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, input(3)))
    .noneMatch(item -> item.adapterId().equals("ARK_SEEDANCE_2_I2V"));
assertThat(catalog.candidates(Task.Kind.VIDEO_GENERATION, input(4)))
    .anyMatch(item -> item.adapterId().equals("ARK_SEEDANCE_2_I2V"));
assertThat(readSettingsJson).doesNotContain(apiKey, "ciphertext", "nonce");
```

- [ ] **Step 2: 运行失败测试。** `cd backend && ./mvnw -Dtest=MediaCloudCapabilityPostgresIT test`；`cd frontend && corepack pnpm test -- MediaSettingsPage.test.tsx PlanApprovalPanel.test.tsx`。
- [ ] **Step 3: 实现声明和 UI。** 注册固定 adapterId、官方 origin、模型、端口、约束、费用来源 `UNKNOWN`（无可信价格时）；配置 API 只接受白名单字段。用户改选经基础层新修订/哈希；保持 `UNTESTED`，不以连通检查冒充真实生成。同步 OpenAPI 后生成 TS。

```java
if (!Set.of("low", "medium", "high").contains(quality))
    throw invalid("GPT Image 2 质量参数无效");
if (durationSeconds < 4 || durationSeconds > 15)
    throw unsupported("Seedance 只支持 4–15 整数秒");
```

- [ ] **Step 4: 验证。** `cd frontend && corepack pnpm api:generate && corepack pnpm typecheck && corepack pnpm test -- MediaSettingsPage.test.tsx PlanApprovalPanel.test.tsx`；`cd backend && ./mvnw -Dtest=MediaCloudCapabilityPostgresIT test`。
- [ ] **Step 5: 提交。** `git add backend/src frontend/src contracts/openapi.yaml && git commit -m "feat: configure fixed GPT Image and Seedance capabilities"`。

### Task 2: GPT Image 2 生成与编辑

**Files:** Create `OpenAiImage2Client.java`, `OpenAiImage2Adapter.java`; modify `AssetService.java` only if existing archive validation lacks PNG bounds; tests `OpenAiImage2ClientTest.java`, `OpenAiImage2PostgresIT.java`.

**Interfaces:** `OpenAiImage2Adapter.submit(AttemptContext)` 使用冻结的提示词/画幅/可选同项目参考图；`OpenAiImage2Client.generate(String key,String prompt,String quality,String size)` 调固定 `/v1/images/generations`；`edit(String key,String prompt,String quality,String size,InputStream reference)` 调固定 `/v1/images/edits`；二者返回 `MediaPayload`。成功返回 `Submission.Completed`，未知响应状态给内核 `Submission.Unknown`。

- [ ] **Step 1: 写失败测试。** 假服务断言无参考图只走 generations、有参考图走 multipart edits；请求模型 `gpt-image-2`、默认 quality `medium`；跨项目参考图/URL 输入拒绝；坏 Base64、非图片、超尺寸拒绝；服务收到请求后断线只留 UNKNOWN 且调度两轮仍仅一次提交。

```java
assertThat(fakeApi.generations()).isEqualTo(1);
assertThat(fakeApi.edits()).isEqualTo(1);
assertThat(fakeApi.lastModel()).isEqualTo("gpt-image-2");
assertThat(unknownTask.status()).isEqualTo(Task.Status.UNKNOWN);
assertThat(fakeApi.submissionsFor(unknownTask.id())).isEqualTo(1);
```

- [ ] **Step 2: 运行失败测试。** `cd backend && ./mvnw -Dtest=OpenAiImage2ClientTest,OpenAiImage2PostgresIT test`；预期无客户端/适配器。
- [ ] **Step 3: 实现固定 HTTP 与归档。** Key 只从历史连接版本解密，固定官方 host/path；测试注入本机假 transport，不开放管理员任意 endpoint。冻结 ArtifactVersion 读取参考图 Asset 字节，校验 MIME/像素/大小；negativePrompt 作为文本并入用户已审提示；Base64 解码后走现有安全 Asset 归档，失败稳定错误码。网络无确定拒绝证据时返回 UNKNOWN，不能重提。

```java
URI target = referenceVersionId == null
    ? URI.create("https://api.openai.com/v1/images/generations")
    : URI.create("https://api.openai.com/v1/images/edits");
request.put("model", "gpt-image-2");
request.put("quality", quality);
```

- [ ] **Step 4: 验证。** `cd backend && ./mvnw -Dtest=OpenAiImage2ClientTest,OpenAiImage2PostgresIT,TaskLeasePostgresIT test`；检查响应丢失、旧 epoch 与归档幂等断言。
- [ ] **Step 5: 提交。** `git add backend/src && git commit -m "feat: add fixed GPT Image 2 adapter"`。

### Task 3: Seedance 提交与原任务轮询

**Files:** Create `ArkSeedanceClient.java`, `ArkSeedance2Adapter.java`; tests `ArkSeedanceClientTest.java`, `ArkSeedancePostgresIT.java`.

**Interfaces:** `ArkSeedanceClient.create(String key,String prompt,byte[] imageBytes,int durationSeconds,String ratio)` 固定官方路径与模型，返回原任务 ID；`query(String key,String originalTaskId)` 只查询该 ID 并返回状态/临时结果地址。适配器向通用内核返回 `Submission.Accepted/Completed/Rejected/Unknown`。

- [ ] **Step 1: 写失败测试。** 假服务核对 Base64 首帧来自用户已选、固定模型与 `generate_audio=false`；4 和 15 秒通过，3/16/小数失败；create 响应丢失但服务已计数时 UNKNOWN 且零二次提交；保存 ID 后只 query 原 ID，空列表不触发重提。

```java
assertThat(fakeArk.lastModel()).isEqualTo("doubao-seedance-2-0-260128");
assertThat(fakeArk.lastGenerateAudio()).isFalse();
assertThat(fakeArk.createdCount()).isEqualTo(1);
assertThat(fakeArk.queriedIds()).containsOnly(savedOriginalId);
```

- [ ] **Step 2: 运行失败测试。** `cd backend && ./mvnw -Dtest=ArkSeedanceClientTest,ArkSeedancePostgresIT test`。
- [ ] **Step 3: 实现提交/轮询。** 只读取已审批的关键帧版本和同项目归档图片；固定北京官方 origin、模型与 JSON 字段；请求前内核保存 SUBMITTING，网络无事务；收到任务 ID 后按 epoch CAS 写 `providerRequestId`；仅用此 ID 查询。明确拒绝与不确定断线映射不同状态，429/5xx 无受理证明时 UNKNOWN。

```java
if (originalTaskId == null) return Submission.unknown("ARK_CREATE_UNCERTAIN");
return Submission.accepted(originalTaskId);
```

- [ ] **Step 4: 验证。** `cd backend && ./mvnw -Dtest=ArkSeedanceClientTest,ArkSeedancePostgresIT,ComfyUiAcceptedCrashPostgresIT test`；预期同一 Task 至多一次 create。
- [ ] **Step 5: 提交。** `git add backend/src && git commit -m "feat: add fixed Seedance task submission and polling"`。

### Task 4: Seedance 下载、无声归档与故障恢复

**Files:** Create `ArkMediaDownloadPolicy.java`; modify `ArkSeedance2Adapter.java`, `AssetService.java`/media verifier as needed, `docs/MVP-SPEC.md`, `docs/DEVELOPMENT-CHECKLIST.md`, `docs/operations/backup-restore.md`; tests `ArkMediaDownloadPolicyTest.java`, `ArkSeedanceArchivePostgresIT.java`, `ProjectEventStreamPostgresIT.java`.

**Interfaces:** `ArkMediaDownloadPolicy.download(URI providerUrl, OutputStream sink, long maxBytes)` 只允许固定 HTTPS 媒体来源和每跳已验证重定向，返回已验证 MP4；`ArkSeedance2Adapter.reconcile(AttemptContext)` 可重查同一任务以取得新临时 URL，完成时返回 `Submission.Completed(MediaPayload)`，绝不 create 新任务。

- [ ] **Step 1: 写失败测试。** 内网 IP、DNS 重绑定、HTTP/非白名单 host、重定向到内网、超字节、伪 MP4 均拒绝；过期 URL 仅重查原 ID；可解码带音轨源产出无声 MP4；磁盘归档失败后重试只下载/归档；取消晚到留历史、不启下游；SSE 重连有结果事件。

```java
assertThatThrownBy(() -> downloads.download(URI.create("https://127.0.0.1/a.mp4"), sink, limit))
    .isInstanceOf(ApiProblemException.class);
assertThat(fakeArk.createdCount()).isEqualTo(1);
assertThat(fakeArk.queriedIds()).containsOnly(savedOriginalId);
assertThat(archivedVideo.hasAudioStream()).isFalse();
```

- [ ] **Step 2: 运行失败测试。** `cd backend && ./mvnw -Dtest=ArkMediaDownloadPolicyTest,ArkSeedanceArchivePostgresIT,ProjectEventStreamPostgresIT test`。
- [ ] **Step 3: 实现下载和归档。** 严格官方响应域 allowlist；每次 DNS 解析验证最终 IP、禁止私网/环回/链路本地、禁止跨域重定向，限制跳数和字节；不让普通输入自带 URL；流式写 scratch 后用现有媒体探测检查 MP4 视频流、时长与音轨，必要时固定 FFmpeg 参数去音轨。临时 URL 过期先 query 原任务取新 URL；无法取得则 BLOCKED。文档列本地假服务覆盖和真实调用“未运行”，并写备份密钥要求。

```java
if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHost(uri.getHost()))
    throw blocked("ARK_MEDIA_URL_REJECTED");
if (!validPublicAddress(resolveAndPin(uri.getHost())))
    throw blocked("ARK_MEDIA_ADDRESS_REJECTED");
```

- [ ] **Step 4: 完整验证。** `cd backend && ./mvnw verify`；`cd frontend && corepack pnpm api:generate && corepack pnpm typecheck && corepack pnpm lint && corepack pnpm test && corepack pnpm build`。再检查 `git diff --check`、无 Key/URL 泄漏、Provider 真实调用状态仍 UNTESTED；如本机无法运行 PostgreSQL/Testcontainers，明确记录未运行。
- [ ] **Step 5: 提交。** `git add backend/src frontend/src contracts/openapi.yaml docs && git commit -m "feat: archive Seedance results safely without resubmission"`。

## Self-review / handoff gate

Task 1–4 覆盖固定能力配置、OpenAI 协议、Seedance 提交/核对、临时结果归档；五条 Review Focus 对应 Task 2、2、1/3、3/4、4。发布说明分别写明 GPT Image 2 与 Seedance 的本地假服务结果及“真实 Provider 调用未运行”；不因为协议测试通过就把云渠道标为 VERIFIED。

执行记录（2026-09-25）：按用户要求跳过红绿灯式测试步骤，完成功能后运行回归。`backend ./mvnw verify`：67 项、0 失败；DNS 策略和过期地址新增断言后的六个相关测试类：13 项、0 失败；前端类型检查、lint、78 项测试及构建通过。两种真实云接口均未调用。
