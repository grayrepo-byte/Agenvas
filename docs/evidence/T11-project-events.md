# T11 持久事件与提交水位证据

任务编号：T11  
变更行为：项目事件以 `project.event_seq` 作为项目内提交水位，先锁项目行，再执行业务变更并写入事件，持锁至同一事务提交。Run、Artifact、Agent 和 Canvas 命令已接入此写入路径；精确重放不生成重复事件。项目快照把项目、画布投影、Agent 配置、活动 Run/Task 与 `snapshotSeq` 放在同一个 PostgreSQL `REPEATABLE READ` 只读事务中读取。  
合约/迁移影响：Flyway V10 增加 `project_event` 及 `(project_id, seq)` 主键、全局 `event_id` 唯一约束；`contracts/openapi.yaml` 增加 `GET /api/v1/projects/{projectId}/snapshot` 和快照 Schema，前端生成类型与客户端同步。  
实际运行的检查：`backend/./mvnw -Dit.test=ProjectEventPostgresIT verify`、`backend/./mvnw -Dit.test=IdentityPostgresIT verify`、`backend/./mvnw -Dit.test=ProjectEventPostgresIT,ArtifactPostgresIT,CanvasPostgresIT,AgentPostgresIT verify`；前端 `corepack pnpm api:generate`、`typecheck`、`lint`、`test`、`build`；`git diff --check`。  
测试结果：上述定向测试均通过。故障注入通过 PostgreSQL trigger 阻断 `agent.run.changed` INSERT，确认 Run、活动槽、幂等记录与事件序号都没有半提交；12 个并发追加事件取得连续序号；并发更新 Run 与读取快照时，`snapshotSeq` 始终与同一时刻的 Run 版本对应。首次全量 `./mvnw verify` 在首个 Testcontainers 容器建立后遇到 Flyway 初次连接失败；身份测试单独复测、随后全量回归均通过。前端 7 个测试与生产构建通过。  
真实 Provider：未调用。  
未验证项：Task 状态全链路事件属于后续切片；事件流当前只包含展示必需 ID/状态，不含完整 Prompt 或私有推理。SSE 交付与 30 天保留清理已在 T12 补充。
