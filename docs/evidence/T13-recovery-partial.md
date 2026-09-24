# T13 取消、UNKNOWN 与恢复分类：阶段证据

本切片尚未完成 T13，也未满足 M2 门禁。当前覆盖持久任务提交未知、取消晚到结果、Mock 内容的 ArtifactVersion CAS 选用，以及新 ComfyUI attempt 在假 HTTP 服务下的原请求核对；没有真实 ComfyUI/GPU 调用或明确风险后的新尝试流程。

变更行为：Flyway V11 增加 `cancel_requested`、提交前的 `provider_attempt` 和 `task_late_result`。内置 TaskWorker 对图片/视频 Task 先持久化 `SUBMITTING` 与 attempt，再调用处理器；确认后保存原 requestId，租约过期的提交由定时扫描转为 `UNKNOWN`，不会回到 READY 或自动重发。Task 创建、提交 checkpoint、确认、完成和恢复状态与项目事件同事务写入。Run 取消先设置任务取消标志，未提交任务变 CANCELED；已运行任务的晚到输出只写入历史，不唤醒下游，失联的已取消本地工作在租约过期后结束。项目快照独立列出最近 100 个 UNKNOWN，即使 Run 已取消、活动槽位已释放仍可见。画布提示可能发生外部费用、不会自动重试，且取消不代表退款。

V12 增加 `task_artifact_target`，在任务创建时钉住目标 Artifact 的当前版本 ID 与聚合版本。同步 Mock 结果创建不可变 Task 版本；只有 Run 未取消、Artifact 未归档且两个预期值均匹配时才自动选用。用户编辑或取消后结果只在历史，Task 输出记载版本 ID 与 selected 标志，SSE 事件使画布刷新。

涉及文件：`backend/src/main/resources/db/migration/V11__provider_attempt_and_cancellation.sql`、`V12__task_artifact_target.sql`、`backend/src/main/java/dev/agenvas/task/`、`backend/src/main/java/dev/agenvas/artifact/`、`backend/src/main/java/dev/agenvas/run/`、项目快照服务与 API、`contracts/openapi.yaml`、生成的前端 API 类型和画布提示。

合约兼容说明：`Task.cancelRequested` 与 `ProjectSnapshot.unknownTasks` 是新的必填响应字段；当前开发阶段按破坏性升级处理，前后端必须同步部署。没有更改既有请求格式。

验证：`TaskRecoveryPostgresIT` 在真实 PostgreSQL 中覆盖提交 checkpoint 后租约过期、UNKNOWN 事件及不再认领；覆盖 Run 取消后晚到结果入历史、依赖任务保持 CANCELED、取消后仍可从快照找到 UNKNOWN。`TaskArtifactSelectionPostgresIT` 覆盖 Mock 内容的正常自动选用、用户编辑后旧结果只留历史、UNKNOWN 后取消再到结果只留历史、越权目标及错误 sourceTaskId 被拒绝。前端有 UNKNOWN 费用提示测试。全量验证结果见依赖基线。

已完成实际进程中断/重启的独立数据库演练，详见 `T13-process-kill-smoke.md`。未验证：真实 ComfyUI 实例的 Provider 原请求核对、人工新建尝试的显式风险确认、浏览器端到端断线恢复、所有 Task 状态的完整事务事件覆盖。因此不得将此切片当作真实媒体提交许可。

2026-09-23 补充：UNKNOWN 警告现可展开最近 100 个待核对任务的类型、步骤、尝试次数、任务 ID、已保存的原 Provider 请求 ID（或明确说明其缺失）、错误码和最近更新时间；不渲染任务输入或模型 Prompt，也不提供直接重试提交按钮。前端 `ProjectWorkspacePage.test.tsx` 验证明细、潜在费用警告与私有输入不显示；26 个前端测试、类型检查和 lint 通过。这只改善人工识别与核对线索，不等于实现了 Provider 对账或新尝试审批；上述未完成项仍然有效。

后续只读提交账本：`GET /api/v1/projects/{projectId}/tasks/{taskId}/attempts` 在 owner/项目/Task 边界内最多返回最近 100 条 `provider_attempt` 的 ID、状态、随机提交关联键、候选/已知原 Provider 请求 ID 与时间；不返回 worker lease、endpoint 指纹、任务输入或凭证。UNKNOWN 卡片仅在操作员展开时读取，并明确说明关联键不是外部已受理或安全重试的证明。`TaskRecoveryPostgresIT` 用真实 PostgreSQL 覆盖 UNKNOWN/ACCEPTED 状态、请求键一致性、未登录 401 与错项目 404；前端组件测试覆盖按需读取与不显示私有输入。OpenAPI 与生成的 TS 类型已同步。真实 Provider 核对和新尝试仍未实现。

本次验证：`./mvnw -q -Dit.test=TaskRecoveryPostgresIT verify` 与 `./mvnw -q verify` 均通过（后者的 Surefire/Failsafe XML 合计 108 个测试、0 failure、0 error，使用 PostgreSQL Testcontainers）；前端 `npm run api:generate`、`npm run typecheck`、`npm run lint`、`npm test -- --reporter=dot`（26 个测试）和 `npm run build` 均通过，构建提示主 JS chunk 超过 500 kB；`git diff --check` 通过。未做真实 ComfyUI 调用、人工 Provider 对账或浏览器端到端演练。

后续新请求的核对线索：ComfyUI 图片与视频提交现显式携带已持久化的 `provider_attempt.request_key` 作为候选 `prompt_id`，且回执必须一致；详情见 `T20-comfyui-protocol-partial.md`。这使响应丢失时有可查询的候选 ID，不等于已经实现原请求核对，也不允许因为历史为空而新建尝试。上述全量验证发生在此改动之前；本改动的定向验证见 T20 证据。

V29/V30 与原请求恢复补充：旧 attempt 的 `candidate_request_id`/`candidate_origin_sha256` 为空。新 ComfyUI 提交前写入候选 prompt ID 与精确 endpoint 指纹；`POST /api/v1/projects/{projectId}/tasks/{taskId}/reconcile` 仅在同一配置/endpoint 下查询 `/history/{id}` 与 `/queue`，要求返回的 prompt ID 与 client ID 都和候选 ID 相同。查询为空返回 `NO_EVIDENCE`，Task/attempt/额度预留不变；找到后用预期 Task 版本及 attempt 条件将原任务恢复 `WAITING_PROVIDER`，不创建新 attempt 或再次 POST `/prompt`。错项目、缺 CSRF、未登录、旧 attempt、取消、endpoint 漂移、身份不匹配均有拒绝路径。`ComfyUiReconciliationPostgresIT` 使用假 HTTP＋真实 PostgreSQL 验证这些路径；前端只为未取消且有候选 ID 的 UNKNOWN 展示核对按钮。该测试没有模拟真实 ComfyUI/GPU，也没有完成“查不到后的显式风险新尝试”或浏览器故障恢复；T13 仍未完成。

合约/迁移兼容：V29/V30 仅追加可空列，旧 attempt 不回填候选 ID 或 endpoint 指纹，因而不会被自动核对；`ProviderAttempt.candidateRequestId` 是可空的新响应字段，endpoint 指纹不进入 API。`reconcile` 是新增 POST；Mock 模式返回明确 409，不存在自动生成回退。开发期前后端应同步部署。检查：定向 `ComfyUiClientTest`、`ComfyUiReconciliationPostgresIT`、图片/视频及 Task 恢复 PostgreSQL 测试通过；首次全量 `./mvnw -q verify` 因九处旧测试硬编码 V28 而失败，同步断言后第二次全量通过（113 个测试、0 failure、0 error）。前端 OpenAPI 类型生成、类型检查、lint、28 个测试和生产构建通过；构建仍提示主 JS chunk 超过 500 kB。`git diff --check` 通过。未做真实 Provider 或浏览器故障演练。

最终围栏补充：原请求恢复的 CAS 还要求关联 Run 未取消或终止；此改动后再次执行完整 `./mvnw -q verify` 通过（113 个测试、0 failure、0 error）。额外的 `TaskRecoveryPostgresIT` 断言确认 endpoint 指纹不进入提交账本 API，定向重跑通过。

页面操作可用性补充：账本响应新增必填 `reconcilable` 布尔值，只在 UNKNOWN attempt 同时具有候选 ID 与原 endpoint 指纹时为真；前端还要求 Task 未取消才展示查询按钮。`ComfyUiReconciliationPostgresIT` 验证可核对记录返回 true 且不泄漏指纹，`TaskRecoveryPostgresIT` 验证旧记录返回 false；两项定向 PostgreSQL 测试、前端 28 个测试、类型检查、lint 与构建均通过。此投影变更发生在上段全量后，不能把上段结果直接当作最后代码状态的全量验证。

最后代码状态再次执行 `./mvnw -q verify` 通过，Surefire/Failsafe 报告合计 113 个测试、0 failure、0 error；`git diff --check` 通过。真实 Provider、显式风险新尝试、浏览器故障演练仍未完成。

2026-09-24 显式风险新尝试补充：V31 新增 `task_manual_replacement`，记录原 UNKNOWN Task、独立新 Task、确认人、原版本和幂等键。`POST /api/v1/projects/{projectId}/tasks/{taskId}/new-attempt` 要求原版本、精确风险确认短语与 Idempotency-Key；服务端重验项目/Run/批准计划/输入与配置/媒体额度后，在同一事务创建新任务和单独预留，保留原 UNKNOWN 与原预留，并重接仍为 PENDING 的下游依赖。已替换的旧 UNKNOWN 不再占据 ComfyUI 提交闸口或阻断待选图片；原请求核对入口则拒绝对已替换任务恢复。页面提供明确的重复费用勾选确认，展示新任务 ID，且不能对已取消或无批准计划的任务发起操作。批准接口幂等重放仍只返回首次批准创建的 attemptNo=1 任务。

验证：`ManualUnknownRetryPostgresIT` 用 Mock 模式和真实 PostgreSQL 覆盖并发同键只创建一份新 Task/预留、原任务保持 UNKNOWN、待执行依赖重接、Run 恢复、ComfyUI 提交闸口可认领新任务、无确认拒绝、不同键冲突、CSRF 与跨项目拒绝；未使用真实 ComfyUI。前端 OpenAPI 类型重新生成，29 个测试、构建、类型检查与 lint 通过。新增迁移后第一次全量后端验证仅因九个旧测试硬编码 V30 失败，同步至 V31 后 `./mvnw -q verify` 通过；补充 HTTP 与认领断言后再跑定向 PostgreSQL 测试通过，`git diff --check` 通过。真实 Provider 提交/计费、浏览器进程中断与 SSE 端到端演练仍未验证。
