# T07 画布与持久化命令证据

任务编号：T07  
变更行为：项目工作区从服务端 CanvasItem 与 Artifact 当前版本投影 React Flow 节点；支持拖拽、缩放、锁定、框选、多选左对齐、适配视图和移除展示；拖拽/缩放结束后提交原子命令批。  
合约/迁移影响：`contracts/openapi.yaml` 增加画布读取与批量命令接口；Flyway V6 增加 CanvasItem 几何、subject、owner/项目外键、版本与范围约束。  
执行环境：macOS / Java 21.0.9 / Node 24.12.0 / Docker Desktop / PostgreSQL 17.11 / React Flow 12.11.6。  
实际运行的检查：`backend/./mvnw verify` 与 Canvas 定向集成测试；前端 `corepack pnpm api:generate/typecheck/lint/test/build`；`docker compose -f deploy/compose.yaml up -d --build`；经 Nginx 的 Canvas API curl 流程。  
测试结果：10 个后端单元测试和 3 个 PostgreSQL 集成测试通过。Canvas 集成测试覆盖放置重放、布局刷新恢复、两命令中第二条冲突时整批回滚、锁定拒绝移动、移除卡片后 Artifact 仍存在。前端 4 个测试通过，覆盖保存失败保留草稿、仅清除已确认草稿、文本框 Delete 不删除节点；类型检查、lint 与生产构建通过。Compose 实测放置 200、布局更新 200、刷新位置 `125.500`/尺寸 `360x220`/版本 1、移除 200、Artifact 仍返回 200。  
状态分工：TanStack Query 保存服务端 Canvas/Artifact；Zustand 仅保存选择、保存状态与布局草稿；React Flow nodes 每次由前两者投影，不维护第三份业务内容。  
真实 Provider：未调用；画布功能不依赖外部模型。  
未验证项：Agent 卡片与输入绑定属于 T08；项目一致性快照与 SSE 增量分别属于 T11–T12。
