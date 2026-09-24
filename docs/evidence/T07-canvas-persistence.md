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

## 2026-09-24 关系层补充

React Flow 现从已保存的 CanvasItem、AgentBinding、当前 ArtifactVersion 输入引用投影三类非执行边：蓝色 Artifact→Agent 为精确版本输入，绿色 Agent→卡片只表示 Agent 输出分组，灰色虚线为当前可见精确版本之间的素材引用。历史绑定明确标记“历史版本”；引用目标若只有旧版本而画布卡片已选新版，则不把线误画到新版，卡片内仍列出该引用的角色、类型和版本 ID。拖动 Artifact 右侧连接点至 Agent 左侧，只调用现有 `updateAgent` CAS 接口追加/替换精确版本绑定；不创建执行计划、媒体 Task 或生成请求。连线错误保留服务端画布状态并显示失败。

`canvasRelations.test.ts` 覆盖三种边的方向/标签、历史版本不误连、输入绑定替换不重复及错误端点拒绝。尚未支持手工 Artifact→Artifact 语义连线、跨历史版本卡片投影或 300 卡片/600 关系浏览器性能验收；不能把这一切片称为规格 6.2–6.4 的完整关系层。无后端、OpenAPI 或 Flyway 改动。

最终前端源码的 `npm run typecheck && npm run lint && npm test && npm run build` 退出码 0，16 个测试文件/51 个测试通过，Vite 构建成功；`git diff --check` 退出码 0。本轮未运行后端测试或真实浏览器拖线交互测试，不能仅凭组件测试断言手势在所有浏览器可用。

## 2026-09-24 手工语义连线补充

Artifact 左侧目标连接点现可接收手工连线。前端仅将图片→角色、图片→场景、角色→镜头、场景→镜头四种关系映射为目标 Artifact 的完整内容修订；引用固定为来源卡片当前精确版本，更新带目标 Artifact `expectedVersion` CAS。场景→镜头替换单一 `sceneVersionId`；其他三种引用追加到对应数组。后端现有 `ArtifactService.revise` 负责身份/项目作用域、不可变版本、Schema/引用校验、CAS 和 `artifact.version.created` 事件；这里未增合约或迁移。连接不会触发 Run、计划或媒体 Task。失败时不乐观画线，保持服务端投影并显示错误。

新增纯函数测试覆盖方向/类型白名单、精确版本、CAS、不可修改源内容、镜头角色追加/场景替换及重复关系无写入。前端 `npm run typecheck && npm run lint && npm test && npm run build` 退出码 0，16 个测试文件/53 个测试通过。运行 `backend/./mvnw -Dtest=ArtifactPostgresIT -DfailIfNoTests=false verify` 时，Surefire `ArtifactPostgresIT` 1/1 通过，Failsafe `LinkArtifactsPostgresIT` 1/1 通过；由于本仓库的 Failsafe 配置，`verify` 仍执行了整批集成测试，最终 `MediaExportPostgresIT` 的 Spring 上下文因 PostgreSQL 连接 EOF（Docker 运行中断）报错，整条命令退出码 1，不能声称后端全量通过。未作浏览器实际拖线测试；性能 300 卡片/600 关系仍待测。

## 2026-09-24 画布回调与 API 补验

新增 `CanvasSemanticConnection.test.tsx`，通过受控 React Flow 回调和 MSW 验证：图片→角色的手工连线发送完整内容及目标 Artifact CAS；只有服务端返回更新后的 Canvas 投影，灰色语义边才出现；相同精确版本再连接不会重复修订；HTTP 409 时继续显示旧投影与冲突提示；不支持的手势给出具体说明且不发修订请求。此测试模拟画布回调，不模拟真实指针拖放。最新前端 `npm run typecheck && npm run lint && npm test && npm run build` 退出码 0，17 个测试文件/56 个测试通过。

重跑 `backend/./mvnw verify -q` 仍退出码 1：46 项 Failsafe 集成测试中 45 项无错误，`LlmProviderConfigPostgresIT` 在 pg_dump/pg_restore 成功后，主机 JDBC 连第二个 PostgreSQL 17 容器时发生 EOF；Surefire 测试及本功能相关的 `ArtifactPostgresIT`、`LinkArtifactsPostgresIT` 均通过。这是全量验收的环境/恢复路径未解决项，不应记作全量通过。

## 2026-09-24 可选语义引用移除

角色/场景的参考图和镜头的角色引用现可在 Artifact 卡片中移除。操作用当前内容构造完整新版本，携带 Artifact `expectedVersion`；后端原有修订路径继续校验作用域、Schema、精确引用和 CAS，不删除历史版本或媒体。镜头的 `sceneVersionId` 必填，界面不提供移除，只能通过合法连线替换；其他诸如选定图片/视频、视频关键帧也不通过此按钮改写。`canvasRelations.test.ts` 新增精确 role/order/version 匹配、源内容不变和必填场景拒绝测试。前端 `npm run typecheck && npm run lint && npm test && npm run build` 退出码 0，17 个测试文件/57 个测试通过。未改后端、OpenAPI 或 Flyway；本轮未重跑后端，也未进行浏览器按钮实测。

## 2026-09-24 移除按钮与冲突状态补验

`ProjectWorkspacePage.test.tsx` 现用实际 React Flow 卡片组件（jsdom）点击“移除引用”，经 MSW 核对完整修订请求、目标 Artifact CAS 和保存后投影更新；先返回 HTTP 409 时，旧引用仍在卡片中，再次提交成功才消失。画布保存状态统一将 HTTP 409 显示为“内容有冲突，当前修改未保存”，区别于普通保存失败；Zustand 草稿不因冲突清除。`canvasStore.test.ts` 验证该状态与草稿并存。前端 `npm run typecheck && npm run lint && npm test && npm run build` 退出码 0，17 个测试文件/59 个测试通过；`git diff --check` 退出码 0。仍未进行真实浏览器拖放和 300/600 性能验收，后端全量 `verify` 的 Docker/PostgreSQL EOF 仍未解决。

## 2026-09-24 真实浏览器连接点修复

在隔离 Compose 与真实 Chrome 中发现：三个镜头各有两条已保存的 `inputReferences`，但 React Flow 实际显示 0 条边；连接点能接收鼠标按下，拖动时却没有连接线，故不会发修订。原因是受控节点每次按服务器画布/交互草稿重新投影时只携带 `initialWidth`/`initialHeight`，未携带 `measured`，库重建内部节点时清空已测量的 `handleBounds`。仅在卡片挂载时请求刷新连接点几何不足以解决后续重建；节点投影现用当前布局尺寸保留 `measured`，让内部连接点边界持续存在。

`frontend/e2e/manual-storyboard-browser.mjs` 经 Chrome DevTools 真实鼠标事件验证：三个镜头的六条精确版本关系边出现；拖动第二名角色到首镜头连接点后显示连接线并创建新内容版本/第七条边；拖动原角色到 Agent 输入连接点后固定版本绑定并出现第八条边。刷新后持久关系仍在；场景改选 v2 时旧 v1 引用不误画到新版卡片。整个过程无 Run/Task。该浏览器路径退出码 0，详细见 `docs/evidence/T06-browser-m1-gate.md`。300 卡片/600 关系性能与跨浏览器兼容仍未验收。
