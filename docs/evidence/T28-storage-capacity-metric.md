# T28 资产卷容量指标：阶段性证据

`StorageCapacityMetrics` 每 30 秒读取资产根目录所在文件系统的一组总字节数与可用字节数，暴露无高基数标签的 `agenvas.storage.disk.total.bytes`、`agenvas.storage.disk.usable.bytes` 与 `agenvas.storage.disk.used.ratio`。根目录尚未创建时，采样最近的真实可写父目录。初次采样前、文件系统读取失败或返回无效容量时三项均为 `-1`，避免把最后一次健康值误作当前容量；恢复后重新显示新值。Actuator metrics 继续使用现有管理员鉴权，不访问素材字节或外部 Provider。

`StorageCapacityMetricsTest` 用临时目录上的真实 FileStore 验证采样，也用注入的故障/恢复值验证 75%→不可用→50% 的状态变化和三个指标均无标签。首次定向编译因测试把 `SimpleMeterRegistry` 误用为 `AutoCloseable` 失败；改为显式 `finally` 关闭后，`cd backend && ./mvnw -q -Dtest=StorageCapacityMetricsTest test` 和完整 `./mvnw -q verify` 均退出 0。当前 Surefire 报告为 3 tests、0 failures/errors，完整 Surefire/Failsafe XML 未见失败或错误；`git diff --check` 通过。无 API 合约或数据库迁移改动。

该比值测量的是文件系统总体使用量，不是 Agenvas 独占用量；容器或宿主机的配额、挂载变化和告警阈值仍须在目标部署上实测。本指标本身不自动发出 80% 告警，也不证明磁盘写满时端到端恢复成功。T28 总门禁保持未完成。
