# T28：SSE 可观测性阶段性证据

2026-09-24。项目事件 Hub 现在提供无标签 Micrometer 指标：`agenvas.sse.connections.active`（当前开放响应）、`agenvas.sse.connections.closed`（已释放响应）、`agenvas.sse.send.failures`（发送异常）和 `agenvas.sse.event.delivery.lag`（事件持久时间戳到服务端 `SseEmitter.send` 返回的时长）。这些指标不使用 projectId、runId、taskId、用户 ID 或事件 ID 作为 label。`/actuator/metrics` 已开放给已认证会话；当前应用账户均为管理员，匿名请求仍被拒绝。端点不触发模型或媒体生成。

`ProjectEventHubTest` 使用 `SimpleMeterRegistry` 验证一个订阅使活跃连接数为 1、关闭后归零、只记一次关闭且所有自定义指标无标签。`ProjectEventStreamPostgresIT` 在真实 PostgreSQL 和 HTTP SSE 回放后验证匿名指标请求为 401、已登录管理员请求为 200、发送延迟计数至少为 1。定向 `./mvnw --batch-mode --no-transfer-progress -Dtest=ProjectEventHubTest -Dit.test=ProjectEventStreamPostgresIT verify -q` 通过。

完整后端 `./mvnw --batch-mode --no-transfer-progress verify -q` 首次运行时，`PromptInjectionPostgresIT` 与 `ToolExecutionPostgresIT` 在 Flyway 初始化阶段遭遇 Testcontainers PostgreSQL 连接失败（SQL State `08001`），未进入业务断言；这两项单独重跑通过，随后完整 `verify` 再跑退出码 0。该环境偶发容器连接故障不能记作业务回归通过的第一次结果。

延迟指标只量到服务端发送返回，不等同浏览器显示延迟；尚未测 300 节点/600 关系、p95、长时间 SSE 内存稳定性或持续负载。T28 的性能与容量门禁保持未勾选。

补充（2026-09-24）：`TaskQueueMetrics` 每 30 秒从 PostgreSQL 一次只读聚合刷新 `agenvas.tasks.current`，只允许 `status=READY|UNKNOWN|BLOCKED` 三个标签值；首次刷新前为 -1。Flyway V33 给这些非终态加部分索引。`TaskArchivedProjectPostgresIT` 在归档阻断任务后显式刷新，验证 `BLOCKED=1`、`UNKNOWN=0` 且指标只有三个固定状态标签，未创建 Provider attempt。定向 `./mvnw --batch-mode --no-transfer-progress -Dit.test=TaskArchivedProjectPostgresIT verify -q` 通过。此快照不衡量队列等待时长，也不等于持续负载测量。

补验：数据库刷新抛出 Spring `DataAccessException` 时，三个状态指标统一返回 -1 而非保留上次计数；下一次成功刷新恢复新计数，故障与恢复仅在状态切换时记录日志。`TaskQueueMetricsTest` 用可控失败加载器验证“健康→不可用→恢复”及固定状态标签；它与 `TaskArchivedProjectPostgresIT` 的组合定向 `verify` 通过。生产库实际断线期间的监控系统告警链路尚未演练。

补充（2026-09-24）：`ActiveRunMetrics` 每 30 秒只读统计 `agent_run` 的非终态（含等待、阻断与请求取消），发布无标签 `agenvas.runs.active`。首次刷新前或数据库不可用时为 -1，恢复后刷新真实计数；不触发调度或生成。`ActiveRunMetricsTest` 验证健康→不可用→恢复和零标签；`TaskArchivedProjectPostgresIT` 创建运行中的 Run 后从真实 PostgreSQL 刷新验证值为 1。定向 `./mvnw --batch-mode --no-transfer-progress -Dtest=ActiveRunMetricsTest -Dit.test=TaskArchivedProjectPostgresIT verify -q` 退出码 0。尚未测大规模 Run 表的统计开销；没有新增合约或迁移。

本次完整 `verify` 运行两次均退出码 1，但失败只发生在不同集成测试的 Flyway 初始化前：第一次 `ShotRedoPostgresIT`、第二次 `LinkArtifactsPostgresIT`，均为 PostgreSQL 连接 `SQL State 08001` / `EOFException`；两项各自单独重跑退出码 0。故本次不能报告完整后端套件通过。需排查 Docker Desktop/Testcontainers 在连续启动约 45 个独立 PostgreSQL 容器时的间歇性连接问题，并再次完成全套验证。

后续补验（2026-09-24）：曾仅在测试配置尝试 Flyway 首次连接最多 3 次、间隔 5 秒重试，确认当前 Spring Boot 4.0.8 支持该属性；一次完整 `verify` 退出码 0。但新增 readiness 测试后的下一次完整 `verify` 仍在 `OpenAiCompatibleGatewayPostgresIT` 的 Flyway 初始化阶段遭遇 `SQL State 08001` / `EOFException`，且每次重试受连接池 60 秒启动等待拖长。该无效重试配置已撤回；最终源码上的完整后端套件本轮未通过，不能把之前一次通过算作最终状态验收。故障源仍待定位。

健康探针补验：readiness 分组明确包含 `readinessState` 与 `db`，liveness 保持独立；`ReadinessPostgresIT` 在隔离真实 PostgreSQL 上先见两个探针均为 HTTP 200，停止数据库后见 readiness 为 503、liveness 仍为 200，定向 `verify` 退出码 0。测试未涵盖上游反向代理实际摘流或自动恢复耗时。

撤回重试配置后的最终定向 `./mvnw --batch-mode --no-transfer-progress -Dit.test=ReadinessPostgresIT,OpenAiCompatibleGatewayPostgresIT verify -q` 退出码 0，含上次完整运行因 Testcontainers 连接失败的用例。由于最终源码没有再次完成整套 `verify`，整套仍标记未验证。

后续 T27 并发测试增强后的同一后端源码，完整 `./mvnw --batch-mode --no-transfer-progress -q verify` 退出码 0，覆盖新增 readiness 测试。该结果取代上一段“整套仍未验证”的时点状态；间歇性 PostgreSQL 容器连接风险仍需持续观察。
