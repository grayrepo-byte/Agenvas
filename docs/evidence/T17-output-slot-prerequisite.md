# T17 新媒体输出槽位：前置证据

这不是完整 ExecutionPlan 或 Approval；T17 和 M3 均未完成。V15 让媒体 Task 的目标可以是已有 Artifact 的精确版本，也可以是计划命名的全新输出槽位。后一种模式在任务创建时不伪造媒体文件或空版本；确认收到并校验结果后，才创建 TASK 来源的 Artifact 与第一个不可变版本。Task 结果包含新 artifactId/versionId。取消后的晚到结果只记为历史、不唤醒下游；重复完成被拒绝。

`TaskNewOutputPostgresIT` 在真实 PostgreSQL 中验证：任务创建后没有占位 Artifact，Mock 结果后才有新图片身份，取消且 UNKNOWN 后的晚到结果不启动依赖任务，同一租约重复提交不创建第二份。首次测试还发现嵌套项目事件序号更新冲突，已改为在 Task 外层事务/事件中创建 Artifact；修复后定向测试和全量 `./mvnw verify` 均通过（14 单元 + 14 PostgreSQL 集成）。Compose 已迁移 V15 且健康。

当前使用 Mock JSON 内的虚拟 assetId，只证明业务状态与版本行为，不证明真实媒体字节归档。尚需 ExecutionPlan/plan_step、DAG 与输入快照校验、Approval/额度预留、批准后任务创建、Provider 复核和输出槽位到下游固定版本的解析。
