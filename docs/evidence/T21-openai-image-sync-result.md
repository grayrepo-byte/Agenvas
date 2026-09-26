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

## 仍未验证

- 参考图编辑（`images/edits`，multipart）只有本地假服务覆盖，未走真实渠道。
- Seedance 真实调用未运行。
- 中转站不遵守 `size`：请求 `1024x1024` 实际返回 `1254x1254`，画幅预设当前不被尊重。
- 归档结果 `selected: false`，未自动选用为用户当前结果；这是既有产品规则（仅在 Run 活动且目标
  产物未被用户改动时自动选用），未经真实链路的选用动作验证。
