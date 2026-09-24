# T09 Run 与项目互斥证据

任务编号：T09  
变更行为：创建 Run 时由服务端固定 Agent 版本、精确输入绑定和不可提升的策略上限快照；Run 状态机持久化 `QUEUED/RUNNING/WAITING_*/BLOCKED/CANCEL_REQUESTED/终态`。每个项目的 `active_run_id` 由 PostgreSQL 项目行锁仲裁；WAITING 与 BLOCKED 继续占槽，取消或成功/失败终态仅在槽位仍属于该 Run 时释放。创建接口要求 `Idempotency-Key`，同 principal/scope/key 与相同请求哈希返回同一个 Run，不同请求哈希返回 `IDEMPOTENCY_CONFLICT`。  
合约/迁移影响：`contracts/openapi.yaml` 增加 Run 创建、读取与取消 API、状态和快照 Schema；Flyway V8 增加 `agent_run`、`idempotency_record`、活动 Run 复合外键、状态/终态时间约束和查询索引。  
并发修正：首次全量回归复现了“先插入带项目外键的 Run，再升级项目行锁”的 PostgreSQL 锁升级死锁；实现已统一为先锁项目并检查空槽，再插入 Run，最后赋值活动槽位，定向测试与再次全量回归均通过。  
实际运行的检查：`backend/./mvnw -Dit.test=AgentRunPostgresIT verify`、修正后的 `backend/./mvnw verify`；前端 OpenAPI 重新生成、类型检查、lint、测试和容器生产构建；`docker compose -f deploy/compose.yaml up -d --build server web`；经 Nginx 的 Run API curl 流程。  
测试结果：10 个后端单元测试和 5 个 PostgreSQL 集成测试通过。Run 集成测试覆盖 8 路同 key 并发只生成一个 Run、不同 key 并发只占一个项目槽位、同 key 异参冲突、输入快照不随 Agent 后续修改、WAITING/BLOCKED 持续占槽、取消/失败释放槽、终态后可创建新 Run、owner 隔离。Compose 实测首次与重放均为 HTTP 202，响应头分别为 `Idempotency-Replayed: false/true` 且 runId 相同；活动槽冲突和异参冲突均为 HTTP 409；取消为 `CANCELED`，随后新 Run 返回 202；Flyway 为 V8。  
真实 Provider：未调用；Run 当前只是可靠执行骨架，没有启动模型或媒体任务。  
未验证项：Task 租约/fencing、事件、SSE、崩溃后的外部请求核对和取消晚到结果属于 T10–T13；前端 Run 按钮在这些基础能力接通前仍禁用。
