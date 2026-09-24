# T27：审批数量与任务数量核对（阶段性）

- 行为：审批面板不再把独立的 `estimate` 当成将创建的任务数量，而是按冻结的执行步骤及任务类型展示图片/视频任务数。若步骤类型、阶段或估算数量不一致，禁用确认并提示重新生成计划；不会因前端文本或模型输出自动审批。
- 服务端机制：`PlanDraftValidator` 从归一化步骤生成估算；`ExecutionPlanService.approveLocked` 每个步骤在同一审批事务中创建一个媒体 Task。未改 API 合约或数据库迁移。
- 测试：前端组件覆盖三图片步骤显示 3 个任务、明确点击才提交审批哈希，以及视频估算与步骤不一致时拒绝点击。PostgreSQL 集成测试核对图片、视频两个阶段各自的估算步骤数与真实 `task` 表数量，并保留并发审批只创建一组任务的断言。
- 实际检查：`frontend` 的定向组件测试 2/2、全量 Vitest 40/40、`tsc --noEmit`、`eslint . --max-warnings=0`、`vite build` 均通过；生产打包有大于 500 kB 的 chunk 提示。`backend` 的 `ExecutionPlanPostgresIT` 定向测试及完整 `./mvnw --batch-mode --no-transfer-progress -q verify` 均退出码 0；`git diff --check` 通过。
- 未验证：这次没有做真实浏览器从展示到落库的跨层端到端计数比对，也没有真实 Provider 调用；§22.2 场景 15 仍为阶段性证据，MVP 总门禁未完成。
