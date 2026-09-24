# T28 持久任务队列等待年龄

`TaskQueueMetrics` 现在用一次 PostgreSQL 只读聚合，同时刷新 READY/UNKNOWN/BLOCKED 数量和 `agenvas.tasks.ready.oldest.age.seconds`。后者是**到期 READY Task 自最近一次状态更新起的最大秒数**；尚未到 `next_action_at` 的任务不计入年龄，空队列为 0。它用于发现待认领任务积压，不等于已完成任务的真实排队时长分位数。指标没有项目、Run、Task 或 Worker ID 标签。数据库不可用或首次尚未采样时为 -1，不沿用旧值；30 秒定期采样不会认领任务或调用 Provider。

验证：`cd backend && ./mvnw -q -Dtest=TaskQueueMetricsTest test` 退出码 0，覆盖数值、故障转 -1、恢复与固定标签；`cd backend && ./mvnw -q -Dit.test=TaskQueueMetricsPostgresIT verify` 退出码 0，在 PostgreSQL 17.11 创建到期及未来 READY Task，确认约 120 秒年龄且年龄 gauge 无标签。新增测试后完整 `cd backend && ./mvnw -q verify` 再次退出 0，Surefire/Failsafe 报告未见失败或错误；`git diff --check` 通过。未测生产负载下查询耗时或完成任务的等待时长分布；T28 性能门禁保持未完成。
