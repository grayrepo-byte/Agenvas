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

## 原因一：租约短于同步调用

`TaskProperties.leaseDuration` 默认 30 秒，`.env`、`compose.yaml`、`application.yaml` 都没有
覆盖它。`beginSubmission` 只改 `status`，不延长 `lease_until`，而 GPT Image 是同步调用 ——
网络等待全部发生在租约内。

| 时刻 (UTC) | 事件 |
|---|---|
| 01:45:45.70 | `beginSubmission` 写入 `SUBMITTING`，`lease_until` = claim 时刻 + 30 秒 |
| 01:45:45.7–01:46:19.7 | 生成请求阻塞，实测约 34 秒 |
| 01:46:15.7 | 租约到期 |
| 01:46:19.72 | 恢复扫描命中，`SUBMITTING` → `UNKNOWN` |
| ≈01:46:19.7 | 结果才返回，租约已被 fencing 拒绝，图片丢弃 |

单次生成 30–40 秒是常态，因此这不是偶发竞态，而是必然失败。

修复：`agenvas.task.lease-duration` 默认改为 `PT30M`（`application.yaml` 与 `compose.yaml`，
`.env.example` 记录可覆盖项）。租约必须覆盖一次完整同步调用。副作用已知：`AgentTurnWorker`
的心跳间隔取 `leaseDuration / 3`，随之从 10 秒变为 10 分钟，worker 崩溃后的恢复窗口同步拉长。
同步媒体提交本身没有心跳，靠租约长度而非续租渡过调用期（`AgentTurnWorker` 的
`heartbeatExecutor` 是既有模式，媒体提交未采用）。

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

## 未验证

修复后的真实重跑尚未进行（需用户在界面触发，UNKNOWN 任务会占用卡片，须先走「明确风险后创建
新尝试」）。因此本文件不主张真实 Provider 归档已跑通，`DEVELOPMENT-CHECKLIST` 中 GPT Image 2
「真实调用未运行」的记录在重跑成功前不作修改。
