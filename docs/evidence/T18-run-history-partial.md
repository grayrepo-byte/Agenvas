# T18 Agent Run 历史入口：阶段性证据

- 行为：Agent 卡片可展开历史。`GET /api/v1/projects/{projectId}/runs?agentId=...` 在 owner、项目、Agent 三重边界内，按 `(created_at,id)` 倒序游标分页，默认 20、最大 100。摘要只含状态、用户指令和时间，不返回模型原始回复、工具参数或私有推理。选中一条记录后，前端复用已有计划和任务列表接口，显示审批阶段、修订、状态、步骤数以及任务类型、步骤、状态、错误码。刷新后仍从数据库读取；SSE 变更时刷新已打开的记录。
- 合约/迁移：`contracts/openapi.yaml` 加法增加 GET Runs 与 `AgentRunSummary`/`AgentRunList`，`frontend/src/shared/api/schema.ts` 重新生成；无数据库迁移。开发阶段旧客户端可不调用新接口，原有单条 Run/计划/任务接口不变。
- 测试：`AgentRunPostgresIT` 在真实 PostgreSQL 验证三条 Run 的倒序分页、损坏游标与超限拒绝、owner 边界、陌生 Agent 404、未登录 401、响应不含 `contextSnapshot`。`RunHistoryPanel.test.tsx` 验证翻页、展开计划和任务，以及不渲染任务私有输入。`./mvnw -q -Dtest=AgentRunPostgresIT -DfailIfNoTests=false verify`（同时运行 Failsafe 集成测试）通过，后续 `./mvnw -q -DskipTests package` 通过；前端 26 个 Vitest 测试、TypeScript 类型检查、lint 和生产构建通过。构建仍有超过 500 kB 的 chunk 警告；`git diff --check` 通过。
- 限制：本次未对历史面板做浏览器刷新实测；前端显示的是可核实的计划/任务状态摘要，不是完整动作账本。真实 LLM 和 ComfyUI 尚未因此得到验证，T18/MVP 门禁仍未完成。
