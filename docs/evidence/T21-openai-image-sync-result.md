# T21：GPT Image 同步结果被丢弃的两个原因

2026-09-26。首次把 `OPENAI_GPT_IMAGE_2` 指向真实中转站（grsai，模型 `gpt-image-2.5`）后，
任务停在 UNKNOWN 且图片丢失。图片在 Provider 侧确实已经生成并计费（中转站后台可见请求与
token 用量），因此这是本地把成功结果丢掉，不是 Provider 失败。

## 观察

`task d7497b18-8ed8-4297-9b5c-d4df27bc377b` 为 `status=UNKNOWN`、
`error_code=PROVIDER_SUBMISSION_UNKNOWN`；对应 `provider_attempt` 同为 `UNKNOWN` 且
`provider_request_id` 为空。两者 `updated_at` 微秒级完全相同（`01:46:19.720319`），说明是同一
事务写入，而不是两次独立的状态变更。

服务端日志给出决定性一行：

```
2026-09-26T01:46:19.758 WARN dev.agenvas.task.application.TaskRecoveryScheduler
  "Classified 1 expired provider submissions as UNKNOWN"
```

`TaskRecoveryScheduler` 以 `fixedDelay = 5_000` 扫描，`recoverExpiredSubmission` 正是把
`SUBMITTING` 且租约过期的任务转成 `UNKNOWN` 并同事务改写 `provider_attempt` 的路径。
`recordSubmission` 的 `Unknown` 分支不写任何状态，因此 UNKNOWN 只能来自这条恢复路径。

## 原因一：读超时短于生成耗时

`OpenAiImage2Client` 的 HTTP 客户端是 `pinned(dns, 10s, 10s, 3min)`，第二个参数读超时只有
**10 秒**。按 OkHttp 语义，读超时是「等待响应首字节」的上限，而该中转站单次生成要数十秒才吐
第一个字节 —— 客户端必然在生成完成前超时，抛 `Uncertain`，adapter 返回 `Submission.Unknown`。

`recordSubmission` 的 `Unknown` 分支不写任何状态，任务就停在 `SUBMITTING`，日志里不会有任何
错误记录。这是本次缺陷难以定位的原因：从数据库只能看到任务停在 SUBMITTING，看不出是网络、
解析还是超时。

第一次任务的时间线与读超时一致：`beginSubmission` 在 `01:45:45.70`，读超时应在 `01:45:55.70`
触发；任务随后空等租约，`01:46:15.7` 租约到期，`01:46:19.72` 被恢复扫描转成 UNKNOWN。上一版
证据把这段 34 秒读成「生成耗时」，实际是「10 秒超时 + 空等租约到期」。

同一缺陷在 `GoogleNanoBananaClient` 同样存在（同为同步图片生成，读超时也是 10 秒），一并改为
3 分钟。`ArkSeedanceClient`（异步提交，单次请求很快）与 `ArkMediaDownloadPolicy`（下载，2 分钟）
不受影响。

修复：两个同步图片客户端的读超时改为 3 分钟，与各自的 callTimeout 同量级。

## 原因二：租约短于同步调用

即使读超时修好，`TaskProperties.leaseDuration` 默认 30 秒仍短于生成耗时：`beginSubmission` 只改
`status`，不延长 `lease_until`，而同步生成的网络等待全部发生在租约内。结果返回时租约已过期，
写回被 fencing 拒绝，图片同样丢弃。

修复：默认改为 `PT30M`（`application.yaml` 与 `compose.yaml`，`.env.example` 记录可覆盖项）。
副作用已知：`AgentTurnWorker` 的心跳间隔取 `leaseDuration / 3`，随之从 10 秒变为 10 分钟，worker
崩溃后的恢复窗口同步拉长。同步媒体提交本身没有心跳，靠租约长度而非续租渡过调用期
（`AgentTurnWorker` 的 `heartbeatExecutor` 是既有模式，媒体提交未采用）。

## 原因二：中转站只回结果 URL

`OpenAiImage2Client.send` 原先硬性要求 `data[0].b64_json` 是文本，否则抛 `Uncertain`。实测该
中转站**即使显式传 `response_format: "b64_json"` 也仍只回 `data[0].url`**（HTTP 200，参数被静默
忽略）；官方 API 回内联 base64，中转站把结果托管到自有 CDN（本次 `file4.aitohumanize.com`，
与 API Base URL 的 `grsai.dakka.com.cn` 不同域）。两种形态叠加时，即使租约足够也无法归档。

修复：结果解析接受 `url` 形态。下载只按 http/https 取回字节，携带 20 MB 上限，**不携带 API
Key**，也不预设中转站的域名、跳转次数或图片格式 —— 重定向照常跟随（CDN 普遍用 302/307 跳到
实际对象存储），格式由归档层 `LocalAssetStorage.storeImage` 的 `inspectImage` 解码判定。下载
失败只重试下载（幂等 GET，不重发已计费的生成请求），三次仍失败才进入 UNKNOWN。

内联 base64 路径保持原有 PNG 校验不变：那里的格式由官方契约确定。

## 验证

- `OpenAiImage2ClientTest` 9 项通过：新增「下载跟随 302」「非图片格式原样返回且非 200 不重试」
  「非 http(s) 协议拒绝」，并断言下载请求不携带 `Authorization`。
- `OpenAiImage2PostgresIT` 通过：新增 url 形态端到端场景（提交 → 下载 → 归档 Asset），
  与既有 base64 场景、丢失响应保持 UNKNOWN、跨项目参考图拒绝共存。
- `PinnedHttpClientsTest` 2 项通过：`pinned` 仍拒绝重定向，新增的
  `pinnedFollowingRedirects` 只用于媒体结果下载。
- 镜像构建期 102 项单元测试通过；`./deploy/update-local.sh` 重建后
  postgres/server/web 均 healthy，容器内 `AGENVAS_TASK_LEASE_DURATION=PT30M`。

中转站行为的实测证据：`response_format` 被忽略；返回 URL 可直连下载（无鉴权、无重定向、
`Content-Type: image/png`、约 2.2 MB）；请求 `1024x1024` 实际返回 `1254x1254`，**该中转站不遵守
`size` 参数**，画幅预设目前不被尊重。

## 真实重跑结果

修复后经界面「明确风险后创建新尝试」重跑成功（2026-09-26 02:47 UTC）：任务 `a9461964` 从
`02:47:23.417` 到 `02:47:58.740`，**耗时 35.3 秒**，状态 SUCCEEDED，结果归档为 IMAGE Asset
（2,163,620 字节，SHA-256 已写入），`step_key = retry.7e5c294f.<version>` 指向原 UNKNOWN 任务。
35 秒远超旧的 10 秒读超时，直接印证原因一；客户端日志无任何告警，说明一次通过、没有触发下载
重试。`DEVELOPMENT-CHECKLIST` 的 GPT Image 2 真实调用状态已相应更新。

同轮一并修复了重跑入口的前端缺陷：项目工作区的待核对任务列表调用面板时漏传 `direct`，而直接
媒体任务的 `planId` 为 `null`、`planned` 因此为假，导致「明确风险后创建新尝试」按钮在该列表里
从不渲染（`UnknownTaskAttemptPanel` 的显示条件是 `planned || direct`）。已在
`ProjectWorkspacePage` 按 `kind` 补齐，并加了回归测试（去掉该参数后用例立即失败）。

## 复发：同一个固定超时常量第二次咬人

2026-09-27 再次实测 `OPENAI_GPT_IMAGE_2`（同一中转站）：**接口 216 秒才返回结果，而当时上限是
180 秒**，客户端先超时，已生成并计费的结果再次被丢弃。这是同一个故障面的第二次复发
（10 秒 → 3 分钟 → 5 分钟），说明「一个必须覆盖整次生成、却写死在客户端里的超时常量」本身就是
脆弱点：上限一旦低于真实耗时，损失的是已经付过费的结果。

同一现象也再次暴露了诊断缺口：`recordSubmission` 的 `Unknown` 分支什么都不写，任务停在
`SUBMITTING` 直到租约（`PT30M`）过期才被恢复扫描统一写成 `PROVIDER_SUBMISSION_UNKNOWN`。
用户在卡片上只看到「结果未知」加一个笼统机器码，必须自己去翻服务端日志。这条与上一节
「原因一」记录的问题完全一致——上一轮只修了超时数值，没有修「原因被丢弃」。

## 本次修复

1. **上限提到 5 分钟**，两个同步图片客户端一致（`OpenAiImage2Client`、`GoogleNanoBananaClient`）。
   两者都用 `PinnedHttpClients.pinned` 的 read/call 两个上限，且都受
   `agenvas.task.lease-duration`（默认 `PT30M`）覆盖。新增不变量：租约必须同时大于调用耗时与
   客户端超时，短于客户端超时会让「当场判定」失效、退化为兜底的笼统码。
2. **本地得到确定结论时立即判定为 UNKNOWN**，不再等租约到期。新增
   `TaskRepository.markSubmissionUnknown` 与 `TaskService.markSubmissionUnknown`，CAS 条件
   镜像 `rejectSubmission`（要求租约仍有效）。与恢复扫描靠 `lease_until` 互斥，任一方先赢。
   CAS 失败**不抛异常**，只记 WARN 并交回扫描——否则调度器吞掉异常后任务照样空等，且只剩笼统码。
3. **每个失败点都带稳定原因码**。`Uncertain` 改为携带 `reasonCode`，适配器原样透传，不再把
   所有情况改写成 `OPENAI_IMAGE_SUBMISSION_UNKNOWN`。新增词汇表
   `dev.agenvas.shared.error.ProviderFailureCodes`：`PROVIDER_CALL_TIMEOUT`、
   `PROVIDER_DOWNLOAD_FAILED`、`PROVIDER_RESPONSE_LOST`、`PROVIDER_PROTOCOL_INVALID`、
   `PROVIDER_RESULT_URL_INVALID`、`PROVIDER_RESPONSE_TOO_LARGE`、`PROVIDER_RESULT_TOO_LARGE`、
   `PROVIDER_SUBMISSION_UNKNOWN`。超时判定由 `shared.http.OutboundTimeouts` 完成：
   `SocketTimeoutException`，或消息精确等于 `timeout` 的 `InterruptedIOException`，并遍历
   cause 与 suppressed——OkHttp 的整次调用超时把底层 socket 异常挂在 cause 上。
4. **前端展示可读原因**：新增 `taskErrorMessages.ts`，卡片、编辑区、Run 对话、运行历史、阻断
   提示与 UNKNOWN 重试面板统一改为「状态 · 可读原因」，未登记的码回退原样显示而不是隐藏。

### 一个被否掉的前端改动

原计划还给「卡片已显示旧图」的情况加一个原因浮层。核实后确认那是死代码：
`displayedMediaAssetId` 与 `showDraft` 共用同一个 `isMediaDraftDisplayed` 判定，两者互为反面，
所以 `assetId` 非空时 `showDraft` 必为 false、`direct-media-tasks` 查询被禁用，浮层永远不会渲染。
而且直接媒体任务排队时后端会把 `displayMode` 置为 `DRAFT`，卡片本就回到显示状态与原因的分支。
前端计划任务的状态按产品规则跟随 Run 对话，不由卡片承载。故未加该浮层。

## 验证

- `OpenAiImage2ClientTest`（16 项）与 `GoogleNanoBananaClientTest`（4 项）通过：新增「注入 300ms
  超时的慢响应 → 原因码为 `PROVIDER_CALL_TIMEOUT`」「断线 → `PROVIDER_RESPONSE_LOST`（不是超时）」
  「结果 URL 404 → `PROVIDER_DOWNLOAD_FAILED` 且只请求一次（非 200 不重试）」
  「`file://` → `PROVIDER_RESULT_URL_INVALID`」「响应形状错误 → `PROVIDER_PROTOCOL_INVALID`」
  「429 → `PROVIDER_SUBMISSION_UNKNOWN`」。
- `OutboundTimeoutsTest`（6 项）通过：`SocketTimeoutException` 与 `InterruptedIOException("timeout")`
  判定为超时；`interrupted`、`deadline reached`、`Canceled` 与形近文案不误判；cause 与 suppressed
  两条穿透路径成立。
- `TaskSubmissionUnknownPostgresIT` 通过（真实 PostgreSQL）：已批准计划的媒体任务当场判定后
  `error_code` 为传入的原因码、租约两列同时清空、`completed_at` 保持为空、provider_attempt 转
  UNKNOWN，**等待中的 Run 转 BLOCKED**（人工重试的前置条件），兜底扫描返回 0，同一租约不可二次
  改写，UNKNOWN 仍占用名额；租约已过期时判定返回 false 且任务保持 SUBMITTING，随后由扫描写成
  `PROVIDER_SUBMISSION_UNKNOWN`。
- `OpenAiImage2PostgresIT`、`GoogleNanoBananaPostgresIT`、`ArkSeedancePostgresIT` 通过：断连后
  **当场**是 UNKNOWN 且 `error_code` 为 `PROVIDER_RESPONSE_LOST`，恢复扫描返回 0。方舟沿用
  `ARK_CREATE_UNCERTAIN`（其超时未改）。
- 前端：`pnpm typecheck`、`pnpm lint` 通过；`pnpm test` 261 项通过（含新增的
  `taskErrorMessages` 映射/回退用例与卡片原因展示用例）。
- 后端单元套件 `./mvnw -o test`：125 项通过。
- `TaskRecoveryPostgresIT`、`ManualUnknownRetryPostgresIT` 通过：进程被杀的兜底恢复与
  UNKNOWN 人工重试（含 Run 阻断作为前置条件）未受本次改动影响。
- `TaskArtifactSelectionPostgresIT`、`ComfyUiImagePostgresIT`、`ComfyUiAcceptedCrashPostgresIT`
  未在本次运行。它们覆盖的是进程被杀与适配器抛异常的兜底路径，本次未改动该路径。
  **未运行，不声称通过。**
- **未做真实 Provider 调用**。因此「216 秒真实结果能正常归档」只由上限提升与上述单元/集成验证
  推断，未实测；中转站是否还有更长的排队时间也无证据。
- 参考图编辑（`images/edits`）仍只有本地假服务覆盖。
- 下载失败现在会给出 `PROVIDER_DOWNLOAD_FAILED`，但仍未验证中转站 CDN 的失败重试在真实链路下的
  行为。

## 仍未验证

- 参考图编辑（`images/edits`，multipart）只有本地假服务覆盖，未走真实渠道。
- Seedance 真实调用未运行。
- 中转站不遵守 `size`：请求 `1024x1024` 实际返回 `1254x1254`，画幅预设当前不被尊重。
- 归档结果 `selected: false`，未自动选用为用户当前结果；这是既有产品规则（仅在 Run 活动且目标
  产物未被用户改动时自动选用），未经真实链路的选用动作验证。
