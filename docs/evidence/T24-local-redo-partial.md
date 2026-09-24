# T24 局部重做：内容修订切片

2026-09-23。此证据证明单镜头内容修订、共享场景引用隔离、Mock 局部媒体重生成和旧任务保护；**尚不证明真实 Provider 生成完成**。

`POST /api/v1/projects/{projectId}/shots/{shotId}/revisions` 接收目标镜头的精确当前版本与 Artifact 乐观版本、完整镜头描述/机位/动作、可选时长和局部场景字段。应用在同一数据库事务里创建新共享场景版本并只让目标镜头的新版本引用它；第一和第三镜头继续引用旧场景版本。目标镜头的新版本清除旧图片/视频选用，但旧镜头版本与媒体引用仍可从历史读取。无场景修改时只创建目标镜头版本。版本冲突与越权检查仍在服务端。

前端镜头卡片提供编辑入口，成功后提示把新镜头版本绑定到 Agent；Agent 卡片可显式选择已绑定的当前镜头，发起限定到该镜头的新 Run。限定 Run 的工具列表只暴露媒体计划，服务端工具执行器也拒绝其他创作工具；计划校验强制恰好一个、且为目标镜头的步骤。Mock 模式会为该镜头分别提出图片和视频计划，每一阶段仍须经过真实用户 API 审批。`ShotRedoPostgresIT` 在 PostgreSQL 17 验证第二镜头隔离、历史保留、旧媒体选用清除、并发旧版本冲突及越权；扩展后的 `MockStoryboardPostgresIT` 验证第二镜头的新 Run 只生成一张图片和一段视频、第一和第三镜头版本未变；`ShotRedoEditor.test.tsx` 验证前端提交精确版本和局部场景修改。OpenAPI 合约与生成 TS 类型已同步。

`TaskStaleShotPostgresIT` 验证旧镜头版本的未提交媒体 Task 在持久化提交检查点被拒绝，Provider handler 未被调用，Task 进入 `BLOCKED/TASK_INPUT_STALE`，无 Provider 请求 ID、无完成时间；已提交任务的晚到结果仍归档为不可变媒体版本，但不自动选用、不放置到画布，也不提升依赖任务。即使另一正常任务后来成功，数据库依赖推进也不会以 `selected=false` 的旧媒体结果解锁下游。检查与镜头修订共用项目事件行锁，避免修订和提交检查点之间出现未受保护的窗口。`ShotRedoPostgresIT` 还覆盖公开 API 的身份、CSRF、成功响应与旧版本冲突。测试使用真实 PostgreSQL 和 Mock 媒体字节，不涉及真实 Provider。

合约变更新增镜头修订路径及请求/响应 schema，另给创建 Run 请求增加可选 `redoShotArtifactId`；旧创建请求不变。仍为开发阶段 `/api/v1`、0.1.0。无数据库迁移，复用不可变 ArtifactVersion、Run 快照与现有事件事务。

补验（2026-09-24）：`ShotRedoPostgresIT` 现在用本地 FFmpeg 生成、经真实 AssetService 归档的 MP4 建立两个 VIDEO Artifact。第一、第三镜头均选用一份精确视频版本，第二镜头选用另一份并同时选用旧图片；修改第二镜头及共享场景后，服务端核对第一、第三镜头当前版本、旧场景引用、选用视频版本和类型化 `selectedVideo` 引用全部不变，第二镜头的新版本清除旧图片与旧视频选择，历史版本保留原选择。测试通过。

同一 PostgreSQL 集成测试新增两个线程同时开始的提交交错：一个修订第二镜头，另一个归档该镜头旧任务已提交的结果。项目事件 `seq` 确定实际提交顺序；若旧结果晚于修订提交，结果 `selected=false`，若旧结果先提交，新镜头修订仍清除旧选择。两种顺序共用断言：最终当前镜头是用户的新版本，没有被旧结果回盖。此测试实际运行了一次并通过；未用调度控制强制每种顺序各出现一次，故不把它当成所有并发时序的穷尽证明。

补验（2026-09-24）：Worker 发现过期镜头输入时现将未提交的旧媒体 Task 置 `BLOCKED/TASK_INPUT_STALE`，不再记为 `FAILED`；状态转换保留租约 fencing，并在批准计划任务的同事务内释放尚未提交的用量预留。Run 被阻断时，前端提示核对最新镜头、停止旧 Run、重新绑定并发起新 Run，再分别审批新图片/视频计划。`TaskStaleShotPostgresIT` 在真实 PostgreSQL 中追加了批准三镜头图片计划后的输入修订：旧 Task 无 Provider 请求而 BLOCKED，Run 同步 BLOCKED，用量预留恰释放一次；取消旧 Run、重新绑定新镜头版本后，新 Run 需重新提出并审批计划才创建新的媒体任务。`BlockedRunNotice.test.tsx` 覆盖该提示。以上测试已通过；这不等于旧计划可直接恢复或真实 Provider 已验收。

仍需实现并验证：真实 Provider 重做、Agent 卡片局部 Run 的浏览器端到端，以及真实三镜头项目全链路未验收。上述 Mock/PostgreSQL 测试只证明任务范围和应用编排，不证明真实模型已生成内容；T24 总门禁仍未通过。
