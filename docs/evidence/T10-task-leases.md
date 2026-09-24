# T10 Task 调度与租约证据

任务编号：T10  
变更行为：Task 与精确输入快照、依赖关系持久化到 PostgreSQL。Worker 使用 `FOR UPDATE SKIP LOCKED` 在短事务中竞争 `READY` 或租约已过期的任务；每次认领递增 fencing epoch，心跳和终态写入必须同时匹配任务状态、Worker、epoch 与有效租约。任务处理器在认领事务提交后才执行，成功、失败和等待 Provider 分别在独立短事务中落库；等待外部状态时清除租约且不占用执行线程。  
合约/迁移影响：Flyway V9 增加 `task`、`task_dependency`、状态/租约/epoch 约束和调度索引；`contracts/openapi.yaml` 增加只读 Task API 与状态 Schema，前端类型和 `getTask` 客户端同步生成。Task 创建和调度当前只供后端应用服务使用，尚未暴露任意客户端创建入口。  
实际运行的检查：`backend/./mvnw -Dit.test=TaskLeasePostgresIT verify`、`backend/./mvnw verify`；前端 `corepack pnpm typecheck`、`lint`、`test` 和生产构建。  
测试结果：10 个后端单元测试和 6 个 PostgreSQL 集成测试通过；前端 5 个测试通过且生产构建成功。Task 集成测试覆盖两个 Worker 只能有一个首次认领、租约过期后由新 Worker 以 epoch 2 接管、epoch 1 的晚到成功被拒绝、epoch 2 的结果成为唯一确认结果、依赖完成后才晋升 READY、等待 Provider 清除租约，以及 owner 隔离。处理器内显式断言不存在环境数据库事务，并完成独立数据库查询，证明网络工作边界位于认领事务之外。  
真实 Provider：未调用；`WAITING_PROVIDER` 只验证持久状态与资源释放语义，尚未验证任何外部生成服务。  
未验证项：外部请求受理后的 UNKNOWN 核对、取消后的晚到结果、自动恢复扫描与实际模型/媒体处理器属于 T13 及后续任务；当前没有常驻自动轮询 Worker。
