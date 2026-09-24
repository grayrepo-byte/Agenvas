# T05 项目和资源归属证据

任务编号：T05  
变更行为：认证用户可创建、读取、游标分页、修改和归档项目；所有查询和写入携带 owner 边界；内容写操作使用 expectedVersion；归档命令可安全重放，归档项目会被活动项目守卫拒绝。  
合约/迁移影响：`contracts/openapi.yaml` 增加项目 CRUD/归档接口与 schema；Flyway V4 增加 owner 外键、状态约束、活动运行槽位、事件序号和乐观版本字段。  
执行环境：macOS / Java 21.0.9 / Node 24.12.0 / Docker Desktop / PostgreSQL 17.11 / Spring Boot 4.0.8。  
实际运行的检查：`backend/./mvnw verify`；前端 `corepack pnpm api:generate/typecheck/lint/test/build`；`docker compose -f deploy/compose.yaml up -d --build`；经 Nginx 的项目 API curl 流程。  
测试结果：8 个单元测试和 1 个 Testcontainers PostgreSQL 集成测试通过；集成测试覆盖真实双用户读取/修改隔离、空更新拒绝、乐观冲突、归档重放、归档活动守卫和游标分页。前端 1 个组件测试通过，类型检查、lint 与生产构建通过。Compose 实测创建 201、更新 200、旧版本更新 409、归档 200、活动列表隐藏归档且包含归档列表可见。  
真实 Provider：未调用；本任务不涉及模型或媒体 Provider。  
未验证项：项目活动运行槽位由 V4 预留，但并发 Run 认领与释放属于 T09；当前活动项目守卫已提供给后续 Run 应用服务复用。
