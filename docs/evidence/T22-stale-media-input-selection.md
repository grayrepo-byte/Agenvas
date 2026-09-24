# T22 输入版本变化后的媒体结果选择（阶段性）

2026-09-24。视频计划创建 Task 时把人工关键帧选择的版本号与图片 Artifact/Version、镜头版本一起写入不可变 Task 输入。媒体任务在提交 Provider 前、已提交结果归档后自动选用前，于项目事件行锁保护下复核这些输入；图片任务还复核 `referenceImageVersionId` 所属图片是否仍为当前版本。输入已过期时，未提交任务进入 `BLOCKED/TASK_INPUT_STALE`，不调用 Provider；已提交结果仍产生历史媒体版本，但 `selected=false`，不修改镜头当前版本、不提升下游。

`MockStoryboardPostgresIT` 使用真实 PostgreSQL、Mock 图片/视频及本地 FFmpeg 完成三镜头双审批闭环，并核对审批后视频 Task 中的关键帧选择版本。其后以数据库选择行变化模拟用户改选：旧视频提交后的归档结果只进入历史，尚未提交的同输入视频由 Worker 阻断，回调未执行。该测试的选择变化由测试夹具直接更新仓储，以隔离 Task 提交/归档栅栏；公开选择 API 的校验另有独立测试，不能把此处称作完整浏览器改选验收。

`TaskStaleShotPostgresIT` 使用真实 PostgreSQL 创建带选定参考图版本的镜头与两项图片任务；在一项已提交、一项未提交时给参考图创建新版本，保持镜头版本不变。图片计划的输入快照现在也包含间接选定的参考图版本和 Artifact 乐观版本；变更后旧计划审批以 `PLAN_CONFLICT` 拒绝。旧已提交结果归档但不自动选用，另一项在网络提交前被阻断。定向 `./mvnw -q -Dit.test=MockStoryboardPostgresIT,TaskStaleShotPostgresIT verify` 曾退出码 0；随后增加计划快照断言，`./mvnw -q -Dit.test=TaskStaleShotPostgresIT verify` 再次退出码 0。

此更改没有新增 API 路径或 Flyway 迁移；`keyframeSelectionVersion` 是服务端构造的 Task 内部输入字段。尚未验证真实 Provider、前端改选交互与所有并发交错，故 T22/M4 总门禁不勾选。

最终源码重新编译后执行全量后端 `./mvnw -q verify`，退出码 0；Surefire/Failsafe XML 报告未发现失败或错误，`git diff --check` 通过。此前一次全量回归在最后的字段类型保护改动前已启动，未作为最终代码的验收结果。

前端补验（2026-09-24）：持久任务统一使用 `TASK_INPUT_STALE` 表示镜头、参考图或人工关键帧变更；阻断提示不再误称必然是镜头正文修改，而是要求核对最新输入、重新绑定当前镜头及所需素材，再经新 Run 分别审批媒体计划。`BlockedRunNotice.test.tsx` 定向 6 项、前端全套 19 文件/71 项、TypeScript 类型检查、ESLint 和 Vite 生产构建均通过。该文案只解释既有稳定错误码，未新增 API 合约或数据库字段，也不等于前端改选的浏览器端到端验收。

运行记录补充区分技术重试与用户重做：已受理请求的查询/归档技术重试只处理原 Provider 请求；内容不满意须基于当前版本发起新 Run 并重新审批，可能有新成本；UNKNOWN 先核对原请求。`RunHistoryPanel.test.tsx` 覆盖三种提示且不渲染私有 Task 输入。最终前端全套 19 文件/71 项、类型检查、lint 与生产构建均退出码 0；本轮未改后端，未重跑 PostgreSQL 集成测试。
