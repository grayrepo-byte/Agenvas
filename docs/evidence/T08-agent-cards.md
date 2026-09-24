# T08 Agent 卡片与输入绑定证据

任务编号：T08  
变更行为：Creator Agent 作为真正的 React Flow 卡片存在于画布，可创建、拖拽、缩放、锁定、移除展示并内联编辑名称与指令；卡片显示 Profile 版本、明确输入数量与精确 ArtifactVersion、空闲/最近操作/待审批状态和独立输出范围。用户可把当前选中的 Artifact 绑定到 Agent，或清空全部输入；空白创建不会隐式读取全项目，也不会启动模型。运行、停止和记录入口已展示但保持禁用，等待 T09–T15 的持久化 Runtime 接通。  
合约/迁移影响：`contracts/openapi.yaml` 增加 Agent 创建、读取、列表和乐观更新接口，以及 `PLACE_AGENT` 画布命令和 Agent 画布投影；Flyway V7 增加 `agent_instance`、`agent_binding`、同项目精确版本复合外键，并扩展 CanvasItem 的 Agent subject 映射。`agent_instance` 只保存稳定 Profile/显示配置与输出组，不保存用户、线程或运行上下文。  
执行环境：macOS / Java 21.0.9 / Node 24.12.0 / Docker Desktop / PostgreSQL 17.11 / React Flow 12.11.6。  
实际运行的检查：`backend/./mvnw verify` 与 Agent 定向 Testcontainers 测试；前端 `corepack pnpm api:generate/typecheck/lint/test/build`；`docker compose -f deploy/compose.yaml up -d --build server web`；经 Nginx 的 Agent、Artifact 和 Canvas API curl 流程。  
测试结果：10 个后端单元测试和 4 个 PostgreSQL 集成测试通过。Agent 集成测试覆盖默认零输入、精确历史版本绑定、错误 Artifact/Version 组合拒绝、跨项目拒绝、陈旧配置 CAS 冲突、owner 边界、Agent 放置重放、画布刷新投影，以及表结构中不存在请求用户/线程上下文字段。前端 5 个测试通过，新增覆盖 Agent 的精确绑定展示和卡片内联配置保存；类型检查、lint 与生产构建通过。Compose 实测 Flyway 为 V7、创建绑定数 1、配置从版本 0 更新到 1、同一 `PLACE_AGENT` 重放后仍只有 1 个卡片、刷新投影为 `AGENT` 且 Artifact 投影为 `null`。  
真实 Provider：未调用；本切片只完成持久化配置和展示，不宣称 Agent Runtime 或模型支持已完成。  
未验证项：运行/停止/记录、活动 Run 互斥、工具执行、审批、任务恢复与结果自动落入输出范围分别属于 T09–T15；Agent 卡片上的对应按钮在这些行为落地前保持禁用。
