# T28 非媒体 API 服务端 p95 观测入口

Spring Boot Actuator 的 `http.server.requests` 现配置应用内 p95 分位值；仍使用框架提供的路由模板 `uri` 等低基数标签，不添加项目、用户、Run、Task 或请求 ID。管理员可通过受保护的 `/actuator/metrics` 排查；这一诊断端点不是生产时序数据库或压测工具。采用 Boot 4 文档列出的 `management.metrics.distribution.percentiles` 配置，见 [Spring Boot Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)。

验证：`cd backend && ./mvnw -q -Dit.test=HttpLatencyMetricsPostgresIT verify` 退出码 0；测试在隔离 PostgreSQL 与真实 Tomcat 上发出 12 次 HTTP 请求，确认 `http.server.requests` 计数与 p95 快照存在，且对应 meter 不含资源 ID 标签。第一次完整 `./mvnw -q verify` 在 `IdentityPostgresIT` 上因测试容器连接认证阶段 EOF 而退出 1；该用例单独重跑通过，第二次完整 `./mvnw -q verify` 退出 0，最终 Surefire/Failsafe 报告无失败或错误，`git diff --check` 通过。保留首次不稳定结果，不把单次绿色重跑解释为已经证明 CI 长期稳定。此测试证明**可观测**，不证明主规格的非媒体 API p95 ≤300 ms 目标，也不涵盖 Run/媒体任务受理、前端事件可见或持续负载；T28 性能门禁保持未完成。
