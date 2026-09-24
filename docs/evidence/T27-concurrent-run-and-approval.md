# T27 故障场景 1–2：并发创建与审批

2026-09-24。`AgentRunPostgresIT` 现对同一项目、Agent、指令和幂等键同时发起 20 次应用服务创建调用：真实 PostgreSQL 最终只有 1 个 `agent_run` 和 1 个初始 `AGENT_TURN` Task；20 个返回均指向同一 Run，其中 19 个为重放。另在独立项目以相同起跑门闩向 Spring Security + MockMvc 控制器并发提交 20 个携带认证、CSRF 和幂等键的 POST：20 个响应均为 202 且 Run ID 一致，恰好一个 `Idempotency-Replayed: false`，数据库仍只含 1 个 Run 和初始 Task。

`ExecutionPlanPostgresIT` 已对同一计划哈希并发执行 20 次审批：只有 1 个新审批，返回同一审批 ID；实际图片任务数量等于计划步骤数（3），用量预留仅 3 笔。模型工具列表不暴露 `approve_plan`。两个类都使用 PostgreSQL 17 Testcontainers；最终新增 HTTP 控制器断言后的定向 `./mvnw --batch-mode --no-transfer-progress -Dit.test=AgentRunPostgresIT verify -q` 与 `./mvnw --batch-mode --no-transfer-progress -Dit.test=ExecutionPlanPostgresIT verify -q` 分别退出码 0。

这覆盖主规格 §22.2 的并发持久化副作用边界；MockMvc 不经过真实网络与反向代理，也未对多 JVM 实例做同规模演练。T27 的其他故障场景、真实 Provider 和完整套件门禁仍单独验收。

最终新增断言后的完整后端 `./mvnw --batch-mode --no-transfer-progress -q verify` 退出码 0；本机此前曾在不同 Testcontainers 启动时发生偶发 PostgreSQL EOF，单次完整通过不等于该环境故障已彻底排除。
