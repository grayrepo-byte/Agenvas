# 调用日志与画布提示迁移

日期：2026-09-26。

## 行为

- 画布移除常驻 UNKNOWN 横幅及其展开账本，也不提供日志入口；调用日志只从侧栏导航进入，进入后可携带当前项目筛选。没有实际待审计划时不显示空审批横幅，真正的计划审批、关键帧选择和所属 Agent 对话的任务状态保持可用。
- `/settings/calls` 复用黑色页面框架、加载态和状态标签。按项目、类型、状态、Trace ID、调用开始时间筛选，服务端分页；详情展示 Provider 请求 ID、Run/Task ID 和稳定错误码。
- 日志详情按需读取关联任务，继续复用原 UNKNOWN 核对和风险确认组件；查看或刷新日志不发起生成，新尝试仍须显式接受可能重复成本。成功后刷新任务和日志。（该处理入口已在同日移除，见文末“后续调整”。）
- 调用结果与业务任务状态分列；收到受理响应不等于产物已生成。历史缺失的响应时间、耗时、Trace ID 显示“未记录”，不补造零值。

## 数据与边界

新增 `V46__provider_call_audit.sql`、`audit` 模块和只读 `GET /api/v1/call-logs`，同步 OpenAPI 与生成的 TypeScript。接口只允许管理员读取本人项目；不返回 Prompt、请求/响应正文、endpoint、凭据或私有推理。迁移只增加新表与索引，不改旧任务或账本；前后端需配套升级才能使用新页面。

模型回合、媒体提交和轮询在调用前持久化记录，调用结束保存响应时间和单调时钟耗时。Trace ID 由服务端生成，进入调用的 MDC 并在退出时恢复父上下文。计时包含适配器内部结果解析、下载或媒体处理，旧 Handler 也可能包含归档；不代表底层 HTTP 首字节延迟，也不包括排队或 Worker 后置结果提交。

已保存模型响应的重放不产生新调用。LLM 模型信息按 Run 固定的配置版本读取；媒体轮询保留原 Provider 请求 ID。审计完成写入失败不能丢弃已经拿到的 Provider 响应或自动重提；未完成记录在租约失活后显示待核对，不伪造响应时间。

旧媒体记录按 Task 聚合原提交账本的最早时间，旧模型记录按回合展示；标记为历史记录，不宣称已还原每次 HTTP 调用。UNKNOWN 状态、配额、预算和恢复规则均不改变。

## 已运行验证

- 前端 `ProjectWorkspacePage` / `PlanApprovalPanel`：2 个文件、28 项测试通过，覆盖计划和直接媒体 UNKNOWN 从全局提示迁移、仅保留导航、空审批隐藏及读取失败仍可见。
- 前端 `CallLogsPage` / `App` / `PageShell` / `UnknownTaskAttemptPanel`：4 个文件原 24 项通过；补充非法筛选提示后 `CallLogsPage` 11 项通过，合计 6 个文件、53 项不同测试。覆盖筛选分页、关联 ID、历史空值、等待/失败/401/403/400、按需账本读取、显式风险重试和刷新。
- 前端 ESLint、TypeScript、Next.js 静态生产构建通过；未运行全量测试。
- `CallLogPostgresIT`：Java 21、PostgreSQL 17.11 Testcontainers，7 项通过，0 失败/错误/跳过。覆盖权限隔离、分页过滤和非法参数、历史缺失值与脱敏、记录写入顺序及响应时刻、异常与失活租约投影。
- 其余后端定向检查：`CallLogServiceTest` 6 项、`ConfiguredChatGatewayAuditTest` 1 项、`OpenAiImage2ClientTest` 11 项，以及 `LlmTurnPostgresIT`、`OpenAiImage2PostgresIT`、`ComfyUiImagePostgresIT`、`TaskRecoveryPostgresIT` 各 1 项通过；后端合计 29 项。验证 Trace 上下文恢复、审计写入失败不丢响应、固定历史模型信息、网络日志脱敏、响应重放去重、媒体与恢复路径。
- Java 21 `test-compile`、`package` 通过。OpenAI/ComfyUI 检查使用本地假 HTTP 服务及真实 PostgreSQL，并非外部 Provider 实测。

检查命令：

```sh
# frontend：定向组件测试，非全量测试
corepack pnpm exec vitest run src/features/canvas/ProjectWorkspacePage.test.tsx src/features/canvas/PlanApprovalPanel.test.tsx
corepack pnpm exec vitest run src/features/settings/CallLogsPage.test.tsx src/app/App.test.tsx src/shared/ui/PageShell.test.tsx src/features/canvas/UnknownTaskAttemptPanel.test.tsx
corepack pnpm lint
corepack pnpm build

# backend：使用 Java 21
./mvnw -q -Dtest=CallLogServiceTest,ConfiguredChatGatewayAuditTest test
./mvnw -q -Dtest=OpenAiImage2ClientTest test
./mvnw -q -Dit.test=CallLogPostgresIT test-compile failsafe:integration-test failsafe:verify
./mvnw -q -Dit.test=LlmTurnPostgresIT,OpenAiImage2PostgresIT,ComfyUiImagePostgresIT test-compile failsafe:integration-test failsafe:verify
./mvnw -q -Dit.test=TaskRecoveryPostgresIT test-compile failsafe:integration-test failsafe:verify
./mvnw -q -DskipTests package
```

OpenAI 图片适配器的日志同时删除原有响应预览、endpoint/host 与异常消息输出，只保留固定错误分类、HTTP 状态、关联安全 ID 和计数；相应脱敏断言已通过。

## Chrome 浏览器验收

使用独立 PostgreSQL、当前后端 JAR 和前端静态导出，在 `localhost:15173` 启动临时 Mock 环境；会话 Cookie 使用独立名称，未覆盖用户 Docker 登录。通过真实 HTTP API 创建的测试项目完成 3 次 Mock LLM 调用与 1 次 Mock 图片调用，生成真实持久审计行；另外单独种入明确命名的“历史待核对演示 · Mock fixture”，含一条 UNKNOWN task/attempt，仅测试历史展示。

Chrome 实际截图及指针操作确认：

- 黑色日志页显示 5 条记录；展开 Mock 图片详情显示服务端保存的 32 位 Trace ID、719 ms 耗时和已完成任务。
- 筛选“待核对”后只显示历史 fixture，响应时间、耗时与 Trace ID 均显示“未记录”；点击后才显示提交账本及原关联键。
- 从详情进入历史项目，该项目快照仍含 UNKNOWN，但画布不再有常驻黄色横幅或空审批提示。
- 从侧栏导航进入调用日志页，加该项目 ID 筛选后恰好显示对应历史记录。
- 画布页确认没有 UNKNOWN 常驻横幅；移除画布日志入口的这次改动由 `ProjectWorkspacePage` 组件测试断言（画布不渲染指向 `/settings/calls` 的链接），未在本轮浏览器中重新核对入口位置。
- 浏览器捕获的控制台 warning/error 为空。

本轮未在浏览器执行新尝试或实际 Provider 核对；风险确认逻辑由定向组件测试覆盖。未进行窄屏视觉验收、长列表性能压测或真实模型调用。

本次没有调用真实收费 Provider，也没有更新用户现有 Docker 部署。

## 后续调整：日志页改为只读（2026-09-26）

上面第 3 条描述的“复用原 UNKNOWN 核对和风险确认组件”已在同日移除。原因：`/api/v1/call-logs` 的 UNKNOWN 是查询投影（写回失败的 `RUNNING` 行，且关联任务不再处于运行中或提交中），因此全部历史记录都会永久显示该标签；LLM 调用记录没有 `taskId`，展开后不存在任何操作入口。

日志页现在只读：调用结果与关联任务的状态标签均为“未知”，详情保留关联任务的当前状态与“前往项目”链接，不再渲染 `UnknownTaskAttemptPanel`，也不再请求提交账本或新尝试接口。管理端系统诊断页的同一状态标签与说明文案同步改为“未知”。UNKNOWN 的显式重试只在 Agent 对话（`AgentRunConversation`）和媒体卡片编辑区（`MediaDraftEditor`）进行，并已进一步简化为单次重试——原请求核对与重复成本勾选同日移除，见 [UNKNOWN 重试简化](T13-unknown-retry-simplification.md)。同步更新了 MVP-SPEC、ADR 0005、任务清单；代价是卡片移出画布期间不再有全局 UNKNOWN 入口，由用户明确接受。

已运行检查：`frontend` 的 `pnpm vitest run src/features/settings/CallLogsPage.test.tsx` 11 项通过，含“关联任务只读、不提供 UNKNOWN 恢复”和“读取关联任务最新状态而非日志状态”两项；改动文件的 ESLint 与 `pnpm typecheck` 通过。未运行全量测试，未在浏览器重新验收，后端未改动。
