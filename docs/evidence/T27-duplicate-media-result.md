# T27：重复媒体完成结果（阶段性证据）

2026-09-24。对应主规格 §22.2 场景 5。`ExecutionPlanPostgresIT` 在真实 PostgreSQL 上先审批三镜头图片计划，并完成三项媒体 Task；随后用同一 Task 租约、同一输出内容和同一 Worker ID 重放已完成任务的结果提交。服务拒绝这次重放，原 Artifact 仍只有一个不可变版本，且该 Task 只有一笔媒体 `SETTLEMENT`。定向 `./mvnw --batch-mode --no-transfer-progress -Dit.test=ExecutionPlanPostgresIT verify -q` 通过。

这证明应用服务的持久完成边界不会因重复结果写入第二个版本或再次结算；测试没有模拟两个外部回调同时到达，也没有进行真实 Provider 调用。T27 总门禁仍未完成。
