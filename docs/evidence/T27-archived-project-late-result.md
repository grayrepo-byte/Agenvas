# T27：项目归档后的媒体任务核对（阶段性证据）

2026-09-24。对应主规格 §7.6、§22.2 场景 12。项目归档后，未到外部提交检查点的媒体任务在同一项目行锁内被阻断为 `BLOCKED/TASK_PROJECT_ARCHIVED`，不调用 Provider handler；已持久化受理 ID 的任务继续按原 ID 核对。晚到结果可写入任务键 Asset 与不可变 ArtifactVersion，但不自动选用、不新建画布输出，也不恢复项目活动状态。普通用户图片/视频上传仍被归档项目守卫拒绝。

`TaskArchivedProjectPostgresIT` 使用 PostgreSQL 17.11 Testcontainers、真实 PNG 解码和本地 FFmpeg 生成的 MP4 验证：归档前受理图片任务、归档后阻断另一未提交图片任务、继续归档已受理结果、保留 Task 状态事件与版本、项目仍为 `ARCHIVED`，且新结果不出现在画布。它还核对任务键图片/视频归档与普通上传的不同守卫。前端 `BlockedRunNotice.test.tsx` 验证新的阻断码以固定文案显示，不暴露 Provider 原始文本。

`TaskStaleShotPostgresIT` 的已批准三镜头计划补验：项目归档后，三个仍未提交的图片任务均被持久阻断，没有调用生成器或创建 `provider_attempt`，且每个 Task 恰好有一笔 `usage_ledger/RELEASE`。定向 `./mvnw --batch-mode --no-transfer-progress -Dit.test=TaskStaleShotPostgresIT verify -q` 通过。

本测试模拟已受理请求并直接完成一次持久轮询租约；未通过真实 ComfyUI/GPU、外部 webhook、独立进程崩溃或完整浏览器路径验证。§22.2 其他场景和 T27 总验收仍未完成。
