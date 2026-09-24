# T12 SSE 与前端重连证据

任务编号：T12  
变更行为：`GET /api/v1/projects/{projectId}/events` 在鉴权和水位有效性检查后提供 SSE，先补发 `after` 或 `Last-Event-ID` 之后的事务事件，再持续读取。浏览器原生重连期间保留序号；重复 seq 不应用，旧 aggregateVersion 不回盖新对象；检测到缺口、无法识别的已知事件 Schema 或游标过期时重新获取一致性快照。默认 15 秒心跳，事件保留 30 天；同项目一次数据库轮询分发给多个连接，每连接最多 128 个待发送帧、全局最多 64 个连接，并使用固定大小发送线程池。服务关闭时主动释放连接和线程。  
合约影响：`contracts/openapi.yaml` 增加 SSE 路径与 `ProjectEvent` Schema；前端 API 类型从权威合约重新生成。无新增数据库迁移，继续使用 V10 事件表。  
实际运行的检查：`backend/./mvnw -Dit.test=ProjectEventStreamPostgresIT -Dtest=ProjectEventHubTest verify`、`backend/./mvnw verify`；前端 `corepack pnpm typecheck`、`lint`、`test`、`build`；`git diff --check`。  
测试结果：11 个后端单元测试和 8 个 PostgreSQL 集成测试通过；前端 7 个测试及生产构建通过。真实 Tomcat HTTP 测试验证未登录 401、会话登录、历史事件补发、`Last-Event-ID` 优先于 URL `after`、跨项目 404，以及 30 天清理后的 `EVENT_CURSOR_EXPIRED` HTTP 409。前端测试验证重复 seq 去重、低版本聚合事件不覆盖新状态、原生重连成功不误重取快照、游标过期或序号缺口后重新快照。队列上限测试验证第 129 个待发送帧触发溢出决策；发送线程池和全局连接数有固定上限。  
未验证项：浏览器端到端弱网与长期多客户端负载尚未压测；目前没有真实 Provider 的 Task 进度事件。后续 Runtime 接入 Task 事件后继续沿用此 SSE 水位协议。
