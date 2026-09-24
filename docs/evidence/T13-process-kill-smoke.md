# T13 实际进程中断与恢复演练

日期：2026-09-23。使用当前 Compose server 镜像和 PostgreSQL 17.11，建立独立临时数据库 `agenvas_recovery_smoke_1790141061040`，未使用现有 `agenvas` 业务数据库。首次 server 容器启动并通过 readiness 后，在临时库人工插入一个合法的 `SUBMITTING` 图片 Task 与一条 `SUBMITTING` provider_attempt，租约设为未来五分钟；该 SQL 种子只用于隔离地模拟已提交的 checkpoint，并不证明业务 API 已调用真实 Provider。

对首次 server 容器执行 `docker kill`，`docker inspect` 确认 `exited|137`。在进程已停止时把该 Task 租约设为过期，确认此刻 Task 与 attempt 仍均为 `SUBMITTING`。随后用同一数据库启动第二个 server 容器，readiness 返回 `UP`。定时恢复扫描后，查询结果：

```text
task.status      UNKNOWN
attempt.status   UNKNOWN
task.error_code  PROVIDER_SUBMISSION_UNKNOWN
task event count 1
lease_epoch      1
attempt count    1
READY count      0
```

这证明新进程没有把不确定提交重新认领或生成第二条 attempt。它与 `TaskRecoveryPostgresIT` 的应用服务 checkpoint 测试共同覆盖恢复边界。演练结束后，两个专用容器均已停止并移除，临时数据库已删除；原 Compose 服务保持运行。浏览器 SSE 在进程中断期间的端到端重连未在本演练验证。
