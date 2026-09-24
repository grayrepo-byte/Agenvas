# T27 场景 3：外部已接收时强杀提交进程

`ComfyUiAcceptedCrashPostgresIT` 使用隔离的 PostgreSQL 17.11 Testcontainers、假 ComfyUI HTTP 服务与打包后的两个真实 Spring Boot JVM。测试通过应用服务创建合法项目、Run 和图片 Task；首个 JVM 的定时提交器将候选 `prompt_id` 和 `SUBMITTING` attempt 提交数据库，再向假服务上传输入图并调用 `/prompt`。假服务读完请求、记录同一 `prompt_id` 后保持连接不返回响应。测试确认 Task 仍为 `SUBMITTING`、数据库候选 ID 与服务收到的 ID 一致且没有保存 `providerRequestId`，随后对首个 JVM 执行强制终止。

首进程退出后，测试使原租约过期，启动第二个 JVM。其恢复扫描将 Task 和唯一 attempt 标为 `UNKNOWN`。第二个提交器再经过完整调度周期，假服务收到的 `/prompt` 总数仍为 1，Task 未回到 `READY`，没有第二条 attempt。两个子进程与测试用容器均在测试结束后清理，不触碰现有 Compose 项目。

验证：`cd backend && ./mvnw -q -Dit.test=ComfyUiAcceptedCrashPostgresIT verify` 连续两次退出码 0，Failsafe 报告 `tests=1, errors=0, failures=0`。首次完整 `./mvnw -q verify` 在无关的 `MockImageSchedulerPostgresIT` 启动阶段因 Testcontainers PostgreSQL 连接中断而退出 1；该项单独重跑退出 0。第二次完整 `./mvnw -q verify` 退出码 0，Surefire/Failsafe XML 未见失败或错误；`git diff --check` 通过。首次全量失败不能抹去，说明本机长测试运行仍可能遇到容器连接波动。

这是本地假服务的故障注入，不证明真实 ComfyUI 的响应或幂等行为，也不覆盖生产网络代理、物理主机故障。
