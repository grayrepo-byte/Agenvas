# T18：持久模型回合上限

2026-09-24。`AgentTurnCommitService` 在第 12 个回合（`stepIndex=11`）收到继续调用工具的响应时，先以当前租约和 epoch 把该 `AGENT_TURN` Task 标记为 `FAILED/MODEL_TURN_LIMIT`，再在同一事务把 Run 转为 `BLOCKED`；不建立第 13 个模型任务。响应和工具结果在此之前已经入持久账本，不能把已执行的只读工具伪装成未发生。前端 `BlockedRunNotice` 从 Task 稳定错误码说明 12 回合上限，不展示模型私有文本。

`AgentTurnSchedulerPostgresIT` 使用始终请求 `read_project_summary` 的假模型、真实 PostgreSQL 和相同持久任务 Worker 验证 12 次模型调用、12 个 `AGENT_TURN` Task、恰好一个带 `MODEL_TURN_LIMIT` 的失败任务及停止后无后续调用；该用例没有 HTTP 客户端。原同类测试独立验证开启的定时调度器在无 HTTP 请求时推进普通 Run。`BlockedRunNotice.test.tsx` 验证稳定诊断文本。

定向后端 `./mvnw -q -Dit.test=AgentTurnSchedulerPostgresIT verify` 与完整 `./mvnw -q verify` 均退出码 0；Surefire/Failsafe XML 没有失败或错误。全量测试期间的 PostgreSQL 连接拒绝日志来自故障注入，未导致测试失败。前端定向 6/6、类型检查、lint、全量 71/71、构建退出码 0。没有变更 OpenAPI、Flyway 或生成的前端类型；没有调用真实 LLM/视频 Provider，也没有实际关闭浏览器或重启服务进程。
