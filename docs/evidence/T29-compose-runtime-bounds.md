# T29 Compose 运行边界：阶段性证据

`deploy/compose.yaml` 为 PostgreSQL、server、web 设置可由 `.env` 覆盖的内存与 CPU 上限，并对各自的 Docker `json-file` 日志限制为三个 10 MiB 文件。server 的 Compose 停机等待期为 45 秒，Spring 关闭阶段为 30 秒，以便短事务和已有请求先结束；这不改变 `SUBMITTING`/`UNKNOWN` 的数据库恢复规则。

本地执行 `docker compose -f deploy/compose.yaml config --quiet` 返回 0；配置 JSON 解析得到 PostgreSQL 768 MiB/1 CPU、server 1536 MiB/2 CPU、web 256 MiB/0.5 CPU、三服务日志轮转和 server `45s` 停机等待。`AGENVAS_SERVER_MEMORY_LIMIT=2g AGENVAS_SERVER_CPUS=3.0` 覆盖验证得到 2 GiB/3 CPU。CI 的 `compose-config` job 增加相同的资源、日志和覆盖断言。修改后的 Spring 配置以 `cd backend && ./mvnw -q -Dit.test=TaskRecoveryPostgresIT verify` 启动 PostgreSQL 与应用上下文，退出码 0；`git diff --check` 通过。未运行完整后端回归。

这些是配置验证，未对现有 Compose 服务执行重建或停机，也不构成实际内存峰值、吞吐、完整停机并发或恢复耗时证据。T29 发布门禁保持未完成。
