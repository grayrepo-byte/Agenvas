# T28 HTTP 请求关联与结构化日志（阶段性）

2026-09-24。服务端为每次 HTTP 请求生成 32 位随机十六进制 `X-Request-Id`，不接受客户端同名请求头作为日志标识。过滤器在请求线程的 MDC 中设置 `requestId` 和 `traceId`，请求结束后清理；默认控制台输出使用 Spring Boot 4.0.8 的 ECS JSON。每次请求的摘要只记方法、状态、是否异步及服务端耗时，不记录 Authorization、Cookie、查询参数或媒体正文。ProblemDetail 的 `traceId` 与响应头相同，方便把 401/业务错误与日志关联；旧的非 HTTP 调用仍生成本地回退 ID。

验证：`RequestCorrelationFilterTest` 覆盖客户端伪造头不被采用、MDC 与 ProblemDetail 对齐、请求后清理和意外异常路径；`HttpLatencyMetricsPostgresIT` 在真实 PostgreSQL＋Tomcat 上验证成功响应的服务端头、401 响应头与 ProblemDetail 一致。定向 `./mvnw -q -Dit.test=HttpLatencyMetricsPostgresIT verify` 退出 0；运行日志实见 ECS JSON 的 `traceId`/`requestId` 字段。这不是完整的分布式 Trace：后台 Worker、异步 SSE 后续发送及 project/run/task/attempt ID 关联仍待补齐。HTTP p95 目标和长期负载尚未测量，T28 门禁保持未完成。

合约仅加法说明全局响应头，未更改 JSON Schema 或数据库迁移。客户端不得把服务端请求 ID 当作认证、幂等或业务资源 ID。
